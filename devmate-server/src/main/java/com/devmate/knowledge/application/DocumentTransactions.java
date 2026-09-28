package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.DocumentMapper;
import com.devmate.knowledge.infrastructure.DocumentRow;
import com.devmate.project.service.ProjectService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.devmate.knowledge.application.StorageState.*;
import static com.devmate.knowledge.application.RemotePhase.*;

/** Only short MySQL transactions. Project locks always precede document locks. */
@Service
public class DocumentTransactions {
    private final DocumentMapper mapper;
    private final ProjectService projects;
    private final KnowledgeProperties properties;
    private final Clock clock;

    public DocumentTransactions(DocumentMapper mapper, ProjectService projects, KnowledgeProperties properties,
                                @Qualifier("knowledgeClock") Clock clock) {
        this.mapper = mapper; this.projects = projects; this.properties = properties; this.clock = clock;
    }

    @Transactional
    public Reservation reserve(long owner, long project, String request, ValidatedDocument input) {
        projects.lockOwnedActiveProject(owner, project);
        LocalDateTime now = now();
        mapper.expireRequest(owner, project, request, now);
        var existing = mapper.request(owner, project, request);
        if (existing != null) {
            if (!input.fingerprint().equals(existing.fingerprint)) throw conflict(ErrorCode.DOCUMENT_REQUEST_CONFLICT);
            if (!"ACTIVE".equals(existing.terminalState)) throw conflict(ErrorCode.DOCUMENT_REQUEST_TERMINATED);
            DocumentRow row = mapper.lock(existing.documentId);
            if (row.storageState == STORED) return new Reservation(row, false);
            throw conflict(row.storageState == UPLOADING ? ErrorCode.DOCUMENT_REQUEST_IN_PROGRESS : ErrorCode.DOCUMENT_REQUEST_TERMINATED);
        }
        mapper.ensureCapacity(owner, project);
        if (mapper.reserveCapacity(owner, project, input.byteSize(), properties.getMaxDocuments(), properties.getMaxProjectBytes()) != 1)
            throw conflict(ErrorCode.DOCUMENT_CAPACITY_EXCEEDED);
        DocumentRow row = new DocumentRow();
        row.ownerUserId = owner; row.projectId = project;
        row.filename = input.filename(); row.fileType = input.fileType(); row.byteSize = input.byteSize(); row.sha256 = input.sha256();
        row.sourceType = "UPLOAD"; row.bucket = properties.getBucket(); row.putToken = UUID.randomUUID().toString();
        row.objectKey = row.putToken;
        row.storageState = UPLOADING; row.remotePhase = NOT_STARTED;
        row.leaseOwner = row.putToken; row.leaseUntil = now.plus(properties.getOperationLease());
        row.nextAttemptAt = row.leaseUntil; row.createTime = now; row.updateTime = now;
        if (mapper.insert(row) != 1 || row.id == null) throw conflict(ErrorCode.INTERNAL_ERROR);
        row.objectKey = "users/" + owner + "/projects/" + project + "/documents/" + row.id + "/" + row.putToken;
        if (mapper.setLocator(row) != 1) throw conflict(ErrorCode.INTERNAL_ERROR);
        mapper.insertRequest(owner, project, request, input.fingerprint(), row.id);
        return new Reservation(row, true);
    }

    @Transactional
    public DocumentRow startPut(long id, long expectedVersion, String putToken) {
        DocumentRow hint = mapper.find(id);
        if (hint == null) throw conflict(ErrorCode.DOCUMENT_REQUEST_TERMINATED);
        boolean active = projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        DocumentRow row = mapper.lock(id);
        if (!active || row == null || row.operationVersion != expectedVersion || row.storageState != UPLOADING
                || row.remotePhase != NOT_STARTED || !Objects.equals(row.leaseOwner, putToken)
                || !row.leaseUntil.isAfter(now())) throw conflict(ErrorCode.DOCUMENT_REQUEST_IN_PROGRESS);
        change(row, value -> value.remotePhase = POSSIBLE);
        return row;
    }

    /** A single PUT success or authoritative rejection proves it cannot write again. */
    @Transactional
    public void acknowledgePut(long id, String putToken) {
        DocumentRow row = internalLock(id);
        if (row == null || !Objects.equals(row.putToken, putToken) || row.remotePhase == FINISHED) return;
        if (row.remotePhase != POSSIBLE) throw conflict(ErrorCode.DOCUMENT_REQUEST_IN_PROGRESS);
        change(row, value -> {
            value.remotePhase = FINISHED; value.nextAttemptAt = now();
            if (Objects.equals(value.leaseOwner, value.putToken)) clearLease(value);
        });
    }

    @Transactional
    public DocumentRow finishUpload(long id, String putToken, boolean validObject) {
        DocumentRow hint = mapper.find(id);
        if (hint == null) throw conflict(ErrorCode.DOCUMENT_REQUEST_TERMINATED);
        boolean active = projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        DocumentRow row = mapper.lock(id);
        if (row == null || !Objects.equals(row.putToken, putToken) || row.remotePhase != FINISHED)
            throw conflict(ErrorCode.DOCUMENT_REQUEST_IN_PROGRESS);
        if (row.storageState == STORED) return row;
        if (row.storageState != UPLOADING) return row;
        change(row, value -> {
            if (!active) { value.storageState = DELETE_PENDING; value.errorCode = "PROJECT_DELETED"; }
            else if (validObject) { value.storageState = STORED; value.errorCode = null; }
            else { value.storageState = FAILED; value.errorCode = "UPLOAD_ABORTED_OR_INVALID"; }
            clearLease(value); value.nextAttemptAt = now();
        });
        return row;
    }

    @Transactional
    public void markDelete(long owner, long project, long id) {
        projects.lockOwnedActiveProject(owner, project);
        DocumentRow row = mapper.lockOwned(owner, project, id);
        if (row == null || row.ownerUserId != owner || row.projectId != project) throw conflict(ErrorCode.DOCUMENT_NOT_FOUND);
        if (row.storageState == DELETE_PENDING) return;
        change(row, value -> {
            value.storageState = DELETE_PENDING;
            value.nextAttemptAt = now();
            // A successful PUT has already ended; the original request no longer needs a lease.
            if (value.remotePhase == FINISHED && Objects.equals(value.leaseOwner, value.putToken)) clearLease(value);
        });
    }

    @Transactional
    public int markDeletedProject(long owner, long project, int limit) {
        if (projects.lockProjectForMaintenance(owner, project)) return 0;
        var ids = mapper.projectCleanupCandidates(owner, project, limit);
        for (long id : ids) {
            DocumentRow row = mapper.lock(id);
            if (row == null || row.storageState == DELETE_PENDING) continue;
            change(row, value -> {
                value.storageState = DELETE_PENDING; value.errorCode = "PROJECT_DELETED";
                value.nextAttemptAt = now();
                if (value.remotePhase == FINISHED && Objects.equals(value.leaseOwner, value.putToken)) clearLease(value);
            });
        }
        return ids.size();
    }

    @Transactional
    public DocumentRow claim(long id, String claimant) {
        DocumentRow row = internalLock(id);
        LocalDateTime now = now();
        if (row == null || row.storageState == STORED || row.needsManual || row.nextAttemptAt.isAfter(now)
                || (row.leaseUntil != null && row.leaseUntil.isAfter(now))) return null;
        change(row, value -> { value.leaseOwner = claimant; value.leaseUntil = now.plus(properties.getOperationLease()); });
        return row;
    }

    @Transactional
    public DocumentRow reconcile(DocumentRow claim, Verification verification) {
        DocumentRow hint = mapper.find(claim.id);
        if (hint == null) return null;
        boolean active = projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        DocumentRow row = mapper.lock(claim.id);
        if (!owns(row, claim)) return null;
        boolean ended = row.remotePhase != POSSIBLE || verification.correlatedWrite();
        if (!ended) return row;
        change(row, value -> {
            value.remotePhase = FINISHED;
            if (!active) { value.storageState = DELETE_PENDING; value.errorCode = "PROJECT_DELETED"; }
            else if (value.storageState == UPLOADING) {
                if (verification.exists() && verification.correlatedWrite() && verification.integrityMatches()) {
                    value.storageState = STORED; value.errorCode = null; value.needsManual = false;
                } else { value.storageState = FAILED; value.errorCode = "UPLOAD_ABORTED_OR_INVALID"; }
            }
            if (value.storageState == STORED) clearLease(value);
        });
        return row;
    }

    @Transactional
    public boolean completeCleanup(DocumentRow claim) {
        DocumentRow row = internalLock(claim.id);
        if (!owns(row, claim) || row.remotePhase != FINISHED || (row.storageState != FAILED && row.storageState != DELETE_PENDING)) return false;
        if (mapper.terminateRequest(row.id, row.storageState == FAILED ? "FAILED" : "DELETED", now().plusHours(24)) != 1
                || mapper.releaseCapacity(row.ownerUserId, row.projectId, row.byteSize) != 1
                || mapper.remove(row.id, row.operationVersion, row.storageState.name()) != 1) throw conflict(ErrorCode.INTERNAL_ERROR);
        return true;
    }

    @Transactional
    public void defer(DocumentRow claim, String safeErrorCode) {
        DocumentRow row = internalLock(claim.id);
        if (!owns(row, claim)) return;
        change(row, value -> {
            value.errorCode = safeErrorCode;
            clearLease(value);
            if (value.retryCount >= properties.getMaxAutomaticRetries()) value.needsManual = true;
            else {
                value.nextAttemptAt = now().plus(Duration.ofMinutes(1L << value.retryCount));
                value.retryCount++;
            }
        });
    }

    @Transactional
    public int purgeExpired() { return mapper.purgeExpired(now(), properties.getScanBatchSize()); }

    private DocumentRow internalLock(long id) {
        DocumentRow hint = mapper.find(id);
        if (hint == null) return null;
        projects.lockProjectForMaintenance(hint.ownerUserId, hint.projectId);
        return mapper.lock(id);
    }
    private boolean owns(DocumentRow row, DocumentRow claim) {
        return row != null && row.operationVersion == claim.operationVersion && row.storageState == claim.storageState
                && Objects.equals(row.leaseOwner, claim.leaseOwner) && row.leaseUntil != null && row.leaseUntil.isAfter(now());
    }
    private void change(DocumentRow row, Consumer<DocumentRow> mutation) {
        String state = row.storageState.name(); long version = row.operationVersion;
        mutation.accept(row); row.operationVersion++; row.updateTime = now();
        if (mapper.save(row, state, version) != 1) throw conflict(ErrorCode.DOCUMENT_REQUEST_IN_PROGRESS);
    }
    private void clearLease(DocumentRow row) { row.leaseOwner = null; row.leaseUntil = null; }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private BusinessException conflict(ErrorCode code) { return new BusinessException(code); }
}
