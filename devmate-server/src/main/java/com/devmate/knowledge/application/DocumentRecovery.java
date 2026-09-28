package com.devmate.knowledge.application;

import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.DocumentMapper;
import com.devmate.knowledge.infrastructure.DocumentRow;
import com.devmate.knowledge.infrastructure.UploadTempFiles;
import com.devmate.project.service.ProjectService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.devmate.knowledge.application.StorageState.*;
import static com.devmate.knowledge.application.RemotePhase.*;

@Component
public class DocumentRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentRecovery.class);
    private final DocumentMapper mapper;
    private final DocumentTransactions transactions;
    private final ProjectService projects;
    private final ObjectStorage storage;
    private final UploadTempFiles temporary;
    private final KnowledgeProperties properties;
    private final Clock clock;
    private final AtomicLong deletedProjectCursor = new AtomicLong();
    public DocumentRecovery(DocumentMapper mapper, DocumentTransactions transactions, ProjectService projects,
                            ObjectStorage storage, UploadTempFiles temporary, KnowledgeProperties properties,
                            @Qualifier("knowledgeClock") Clock clock) {
        this.mapper = mapper; this.transactions = transactions; this.projects = projects;
        this.storage = storage; this.temporary = temporary; this.properties = properties; this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${devmate.knowledge.scan-interval:PT60S}", initialDelayString = "${devmate.knowledge.scan-interval:PT60S}")
    public void scheduled() { if (properties.isSchedulingEnabled()) runOnce(); }

    public int runOnce() {
        String traceId = MDC.get("traceId");
        try (var trace = MDC.putCloseable("traceId", traceId == null ? UUID.randomUUID().toString() : traceId)) {
            return scanOnce();
        }
    }
    private int scanOnce() {
        temporary.sweep(properties.getScanBatchSize());
        if (!properties.isEnabled()) return 0;
        int claimed = 0;
        try {
            discoverDeletedProjects();
            var ids = mapper.due(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), properties.getScanBatchSize());
            for (long id : ids) {
                DocumentRow row = null;
                try {
                    row = transactions.claim(id, UUID.randomUUID().toString());
                    if (row == null) continue;
                    claimed++;
                    recover(row);
                } catch (RuntimeException error) {
                    // The durable lease is retained if MySQL cannot confirm this update.
                    LOG.warn("Document recovery deferred traceId={} documentId={} code=STATE_UNCONFIRMED", MDC.get("traceId"), id);
                }
            }
            transactions.purgeExpired();
        } catch (RuntimeException error) { LOG.warn("Document scan deferred traceId={} code=STATE_UNCONFIRMED", MDC.get("traceId")); }
        return claimed;
    }
    private void discoverDeletedProjects() {
        int remaining = properties.getScanBatchSize();
        var deleted = projects.deletedProjectsForMaintenance(deletedProjectCursor.get(), properties.getScanBatchSize());
        if (deleted.isEmpty()) { deletedProjectCursor.set(0); return; }
        for (var project : deleted) {
            int count = transactions.markDeletedProject(project.ownerUserId(), project.id(), remaining);
            remaining -= count;
            if (remaining == 0) { deletedProjectCursor.set(project.id() - 1); return; }
            deletedProjectCursor.set(project.id());
        }
    }
    private void recover(DocumentRow row) {
        try {
            if (row.storageState == UPLOADING || row.remotePhase != FINISHED) {
                Verification found = row.remotePhase == NOT_STARTED ? Verification.missing()
                        : storage.inspect(row.location(), row.byteSize, row.sha256, row.putToken);
                row = transactions.reconcile(row, found);
                if (row == null || row.storageState == STORED) return;
                if (row.remotePhase == POSSIBLE) { defer(row, "WRITE_OUTCOME_UNKNOWN"); return; }
            }
            if (row.storageState != FAILED && row.storageState != DELETE_PENDING) return;
            storage.delete(row.location());
            if (storage.inspect(row.location(), row.byteSize, row.sha256, row.putToken).exists()) {
                defer(row, "DELETE_UNCONFIRMED"); return;
            }
            if (transactions.completeCleanup(row)) LOG.info("Document operation traceId={} documentId={} operation=cleanup state=COMPLETED", MDC.get("traceId"), row.id);
        } catch (StorageFailure error) {
            defer(row, error.errorCode() == com.devmate.common.api.ErrorCode.KNOWLEDGE_STORAGE_TIMEOUT ? "STORAGE_TIMEOUT" : "STORAGE_UNAVAILABLE");
        }
    }
    private void defer(DocumentRow row, String code) {
        if (row == null) return;
        try { transactions.defer(row, code); }
        catch (RuntimeException error) { LOG.warn("Document recovery state unconfirmed traceId={} documentId={}", MDC.get("traceId"), row.id); }
        LOG.warn("Document recovery deferred traceId={} documentId={} code={}", MDC.get("traceId"), row.id, code);
    }
}
