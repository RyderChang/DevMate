package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.config.ProcessingProperties;
import com.devmate.knowledge.config.IndexProperties;
import com.devmate.knowledge.infrastructure.DocumentMapper;
import com.devmate.knowledge.infrastructure.IndexJournal;
import com.devmate.knowledge.infrastructure.DocumentRow;
import com.devmate.knowledge.infrastructure.ProcessingMapper;
import com.devmate.knowledge.infrastructure.ProcessingRequestRow;
import com.devmate.knowledge.infrastructure.ProcessingRow;
import com.devmate.knowledge.vo.ProcessingResponse;
import com.devmate.project.service.ProjectService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Project -> document -> generation locks serialize quota, deletion, staging and publication. */
@Service
public class ProcessingTransactions {
    private final ProcessingMapper mapper;
    private final DocumentMapper documents;
    private final ProjectService projects;
    private final ProcessingProperties properties;
    private final KnowledgeProperties storageProperties;
    private final Clock clock;
    private final IndexJournal indexes;
    private final IndexProperties indexing;
    public ProcessingTransactions(ProcessingMapper mapper, DocumentMapper documents, ProjectService projects,
            ProcessingProperties properties, KnowledgeProperties storageProperties, @Qualifier("knowledgeClock") Clock clock, IndexJournal indexes, IndexProperties indexing) {
        this.mapper = mapper; this.documents = documents; this.projects = projects;
        this.properties = properties; this.storageProperties = storageProperties; this.clock = clock;
        this.indexes = indexes;
        this.indexing = indexing;
    }
    public boolean enabled() { return properties.isEnabled() && storageProperties.isEnabled(); }

    @Transactional
    public ProcessingStart start(long owner, long project, long id, String request) {
        DocumentRow document = owned(owner, project, id);
        if (!enabled()) throw error(ErrorCode.DOCUMENT_PROCESSING_DISABLED);
        if (document.storageState != StorageState.STORED) throw error(ErrorCode.DOCUMENT_NOT_STORED);
        var previous = mapper.request(owner, project, request);
        if (previous != null && previous.expiresAt != null && !previous.expiresAt.isAfter(now())) {
            mapper.removeRequest(previous.id, now()); previous = null;
        }
        if (previous != null) {
            if (!previous.fingerprint.equals(fingerprint(document, previous.parserVersion, previous.strategyVersion)))
                throw error(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
            if (previous.processingId == null) throw error(ErrorCode.DOCUMENT_PROCESSING_TERMINATED);
            var row = mapper.find(previous.processingId);
            if (row == null || terminal(row)) throw error(ErrorCode.DOCUMENT_PROCESSING_TERMINATED);
            return new ProcessingStart(response(row, mapper.active(id)), !"CHUNKED".equals(row.state));
        }
        if (mapper.documentRequests(owner, project, id) >= 100 || mapper.projectRequests(owner, project) >= 10_000)
            throw error(ErrorCode.DOCUMENT_PROCESSING_REQUEST_LIMIT);
        if (mapper.inFlight(id) != 0) throw error(ErrorCode.DOCUMENT_PROCESSING_IN_PROGRESS);
        var active = mapper.active(id);
        if (active != null && active.sourceSha256.equals(document.sha256) && TextChunker.PARSER.equals(active.parserVersion)
                && TextChunker.STRATEGY.equals(active.strategyVersion)) {
            bind(document, request, active, false);
            return new ProcessingStart(response(active, active), false);
        }
        if (mapper.residual(id) != 0 || mapper.generations(id) >= 2) throw error(ErrorCode.DOCUMENT_PROCESSING_IN_PROGRESS);
        mapper.ensureCapacity(owner, project);
        if (mapper.reserve(owner, project, TextChunker.MAX_TEXT_BYTES) != 1) throw error(ErrorCode.DOCUMENT_CHUNK_CAPACITY_EXCEEDED);
        ProcessingRow row = new ProcessingRow();
        row.ownerUserId = owner; row.projectId = project; row.documentId = id;
        row.generation = mapper.nextGeneration(id); mapper.advanceGeneration(id);
        row.sourceSha256 = document.sha256; row.parserVersion = TextChunker.PARSER; row.strategyVersion = TextChunker.STRATEGY;
        row.state = "PENDING"; row.reservedBytes = TextChunker.MAX_TEXT_BYTES;
        row.createTime = now(); row.updateTime = row.createTime; row.nextAttemptAt = row.createTime;
        require(mapper.insert(row) == 1 && row.id != null);
        bind(document, request, row, true);
        mapper.prune(id, row.id);
        return new ProcessingStart(response(row, active), true);
    }
    private void bind(DocumentRow document, String request, ProcessingRow row, boolean creator) {
        var mapping = new ProcessingRequestRow();
        mapping.ownerUserId = row.ownerUserId; mapping.projectId = row.projectId; mapping.documentId = row.documentId;
        mapping.clientRequestId = request; mapping.fingerprint = fingerprint(document, row.parserVersion, row.strategyVersion);
        mapping.processingId = row.id; mapping.generation = row.generation; mapping.sourceSha256 = row.sourceSha256;
        mapping.parserVersion = row.parserVersion; mapping.strategyVersion = row.strategyVersion;
        mapping.terminalState = row.state; mapping.creator = creator; mapping.expiresAt = creator ? null : now().plusHours(24);
        require(mapper.insertRequest(mapping) == 1);
    }
    @Transactional
    public ProcessingResponse status(long owner, long project, long id) {
        owned(owner, project, id);
        return response(mapper.latest(id), mapper.active(id));
    }
    /** Authorization and visibility are checked in the same transaction that obtains the bounded page. */
    @Transactional
    public List<TextChunk> readActive(long owner, long project, long id, int offset, int limit) {
        owned(owner, project, id);
        if (offset < 0 || offset > TextChunker.MAX_CHUNKS || limit < 1 || limit > 100) throw error(ErrorCode.INVALID_PARAMETER);
        var active = mapper.active(id);
        return active == null ? List.of() : mapper.chunks(active.id, offset, limit);
    }
    @Transactional
    public ProcessingRow claim(long id) {
        var hint = mapper.find(id);
        if (hint == null) return null;
        boolean projectActive = projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        var document = documents.lock(hint.documentId);
        var row = mapper.lock(id);
        if (row == null || !("PENDING".equals(row.state) || "PROCESSING".equals(row.state))) return null;
        if (!projectActive || document == null || document.storageState != StorageState.STORED) { cancelDocument(row.documentId); return null; }
        if (!enabled()) { if ("PROCESSING".equals(row.state)) pause(row); return null; }
        if (row.nextAttemptAt.isAfter(now()) || row.leaseUntil != null && row.leaseUntil.isAfter(now())) return null;
        if (!TextChunker.PARSER.equals(row.parserVersion) || !TextChunker.STRATEGY.equals(row.strategyVersion)) {
            row.state = "FAILED"; row.errorCode = "PROCESSING_VERSION_UNAVAILABLE"; clearLease(row); save(row); return null;
        }
        // Expired attempts become a new version before deleting any of their staging rows.
        if ("PROCESSING".equals(row.state)) pause(row);
        if (!cleanBatch(row, true)) return null;
        row.state = "PROCESSING"; row.errorCode = null; row.chunkCount = 0; row.textBytes = 0;
        row.attemptCount++;
        row.leaseOwner = UUID.randomUUID().toString(); row.leaseUntil = now().plusMinutes(2); row.nextAttemptAt = row.leaseUntil;
        save(row); return row;
    }
    @Transactional
    public DocumentRow source(ProcessingRow claim) {
        var row = guarded(claim);
        return row == null ? null : documents.find(row.documentId);
    }
    @Transactional
    public ProcessingRow stage(ProcessingRow claim, List<TextChunk> chunks) {
        var row = guarded(claim);
        if (row == null) return null;
        if (chunks.isEmpty() || chunks.size() > 128) throw error(ErrorCode.INVALID_PARAMETER);
        long bytes = 0;
        for (int i=0; i<chunks.size(); i++) {
            if (chunks.get(i).ordinal() != row.chunkCount + i) throw error(ErrorCode.INTERNAL_ERROR);
            bytes += chunks.get(i).byteSize();
        }
        require(bytes <= row.reservedBytes && row.chunkCount + chunks.size() <= TextChunker.MAX_CHUNKS);
        require(mapper.insertChunks(row, chunks) == chunks.size());
        row.reservedBytes -= bytes; row.usedBytes += bytes; row.chunkCount += chunks.size(); row.textBytes += bytes;
        save(row); return row;
    }
    @Transactional
    public boolean publish(ProcessingRow claim, ParsedDocument parsed) {
        var row = guarded(claim);
        if (row == null) return false;
        require(row.chunkCount == parsed.chunks().size() && row.textBytes == parsed.textBytes()
                && mapper.countChunks(row.id) == row.chunkCount);
        // Verify the complete ordered manifest, including content, before making any generation visible.
        for (int offset=0; offset<row.chunkCount; offset+=128) {
            int end = Math.min(offset + 128, row.chunkCount);
            require(mapper.chunks(row.id, offset, 128).equals(parsed.chunks().subList(offset, end)));
        }
        var active = mapper.current(row.documentId);
        if (active != null) { indexes.cancel(row.documentId, now()); active.active = false; save(active); }
        release(row, row.reservedBytes); row.reservedBytes = 0;
        row.normalizedSha256 = parsed.normalizedSha256(); row.state = "CHUNKED"; row.active = true;
        clearLease(row); save(row); require(mapper.setActive(row.documentId, row.id) == 1);
        return true;
    }
    @Transactional
    public void fail(ProcessingRow claim, String code, boolean transientFailure) {
        var row = guarded(claim);
        if (row == null) return;
        row.errorCode = code; clearLease(row);
        if (transientFailure && row.retryCount < 3) {
            row.state = "PENDING"; row.nextAttemptAt = now().plusMinutes(1L << row.retryCount); row.retryCount++;
        } else row.state = "FAILED";
        save(row);
    }
    @Transactional
    public void pauseClaim(long id) {
        var hint = mapper.find(id);
        if (hint == null) return;
        projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId); documents.lock(hint.documentId);
        var row = mapper.lock(id);
        if (row != null && "PROCESSING".equals(row.state)) pause(row);
    }
    private void pause(ProcessingRow row) { row.state = "PENDING"; clearLease(row); row.nextAttemptAt = now(); save(row); }

    /** Called with the parent project/document locks already held, including project deletion events. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelDocument(long id) {
        indexes.cancel(id, now());
        require(mapper.setActive(id, null) == 1);
        for (long record : mapper.documentRecords(id)) {
            var row = mapper.lock(record);
            if ("CANCELLED".equals(row.state)) continue;
            row.active = false; row.state = "CANCELLED"; row.errorCode = "DOCUMENT_DELETED";
            clearLease(row); save(row);
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelProject(long owner, long project) {
        for (long id : documents.allProjectDocuments(owner, project)) { documents.lock(id); cancelDocument(id); }
    }
    @Transactional
    public boolean cleanup(long id) {
        var hint = mapper.find(id);
        if (hint == null) return true;
        boolean active = projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        var document = documents.lock(hint.documentId); var row = mapper.lock(id);
        if (row == null) return true;
        if (!active || document.storageState != StorageState.STORED) { cancelDocument(row.documentId); row = mapper.lock(id); }
        if (row.active || "PENDING".equals(row.state) || "PROCESSING".equals(row.state)) return false;
        return cleanBatch(row, false);
    }
    private boolean cleanBatch(ProcessingRow row, boolean retry) {
        var batch = mapper.chunks(row.id, 0, 128);
        long bytes = batch.stream().mapToLong(TextChunk::byteSize).sum();
        require(bytes <= row.usedBytes); require(mapper.deleteChunks(row.id, 128) == batch.size());
        row.usedBytes -= bytes;
        if (retry) row.reservedBytes += bytes; else release(row, bytes);
        boolean complete = mapper.countChunks(row.id) == 0;
        if (complete) {
            require(row.usedBytes == 0);
            if (!retry) {
                release(row, row.reservedBytes); row.reservedBytes = 0;
                mapper.terminateRequests(row, now().plusHours(24));
            }
        }
        save(row); return complete;
    }
    /** Parent deletion waits explicitly for both reservations and all durable chunk rows. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean removeForParent(long id) {
        for (long record : mapper.documentRecords(id)) {
            var row = mapper.lock(record);
            if (row.active || row.usedBytes != 0 || row.reservedBytes != 0 || mapper.countChunks(record) != 0
                    || "PENDING".equals(row.state) || "PROCESSING".equals(row.state)) return false;
            mapper.terminateRequests(row, now().plusHours(24));
        }
        mapper.detachRequests(id); mapper.removeDocumentRecords(id); return true;
    }
    @Transactional
    public void purgeCandidate(ProcessingRequestRow hint) {
        projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        var row = mapper.lockRequest(hint.id);
        if (row == null || row.expiresAt == null || row.expiresAt.isAfter(now())) return;
        mapper.removeRequest(row.id, now());
        var latest = mapper.latest(row.documentId);
        if (latest != null) mapper.prune(row.documentId, latest.id);
    }
    private ProcessingRow guarded(ProcessingRow claim) {
        var hint = mapper.find(claim.id);
        if (hint == null) return null;
        boolean active = projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        var document = documents.lock(hint.documentId); var row = mapper.lock(claim.id);
        if (row == null || !"PROCESSING".equals(row.state) || row.operationVersion != claim.operationVersion
                || !Objects.equals(row.leaseOwner, claim.leaseOwner) || row.leaseUntil == null || !row.leaseUntil.isAfter(now())) return null;
        if (!active || document.storageState != StorageState.STORED) { cancelDocument(row.documentId); return null; }
        if (!enabled()) { pause(row); return null; }
        if (!row.sourceSha256.equals(document.sha256)) {
            row.state = "FAILED"; row.errorCode = "INTEGRITY_MISMATCH"; clearLease(row); save(row); return null;
        }
        return row;
    }
    private DocumentRow owned(long owner, long project, long id) {
        try { projects.lockOwnedActiveProject(owner, project); }
        catch (BusinessException error) { if (error.getCode() == 404) throw error(ErrorCode.DOCUMENT_NOT_FOUND); throw error; }
        var document = documents.lockOwned(owner, project, id);
        if (document == null || document.storageState == StorageState.DELETE_PENDING) throw error(ErrorCode.DOCUMENT_NOT_FOUND);
        return document;
    }
    private String fingerprint(DocumentRow document, String parser, String strategy) {
        return TextChunker.sha((document.id + ":" + document.sha256 + ":" + parser + ":" + strategy).getBytes(StandardCharsets.UTF_8));
    }
    private boolean terminal(ProcessingRow row) { return "FAILED".equals(row.state) || "CANCELLED".equals(row.state); }
    private ProcessingResponse response(ProcessingRow latest, ProcessingRow active) {
        return new ProcessingResponse(latest == null ? null : latest.summary(), active == null ? null : active.summary(),
                "NORMALIZED_UNICODE_CODE_POINT", indexing.isEnabled() && enabled() && active != null && indexes.active(active.documentId) != null);
    }
    private void release(ProcessingRow row, long bytes) { if (bytes != 0) require(mapper.release(row.ownerUserId, row.projectId, bytes) == 1); }
    private void clearLease(ProcessingRow row) { row.leaseOwner = null; row.leaseUntil = null; }
    private void save(ProcessingRow row) { long version = row.operationVersion++; row.updateTime = now(); require(mapper.save(row, version) == 1); }
    private void require(boolean condition) { if (!condition) throw error(ErrorCode.INTERNAL_ERROR); }
    private BusinessException error(ErrorCode code) { return new BusinessException(code); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
