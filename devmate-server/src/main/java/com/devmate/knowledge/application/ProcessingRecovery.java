package com.devmate.knowledge.application;

import com.devmate.knowledge.config.ProcessingProperties;
import com.devmate.knowledge.infrastructure.ProcessingMapper;
import com.devmate.knowledge.infrastructure.ProcessingRow;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persistent bounded scans; a restarted process needs neither an in-memory queue nor the original request. */
@Component
public class ProcessingRecovery implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingRecovery.class);
    private final ProcessingMapper mapper;
    private final ProcessingTransactions transactions;
    private final ProcessingProperties properties;
    private final ObjectStorage storage;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    public ProcessingRecovery(ProcessingMapper mapper, ProcessingTransactions transactions, ProcessingProperties properties,
            ObjectStorage storage, @Qualifier("knowledgeClock") Clock clock) {
        this.mapper = mapper; this.transactions = transactions; this.properties = properties; this.storage = storage; this.clock = clock;
    }
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void scheduled() {
        if (!properties.isSchedulingEnabled()) return;
        maintain();
        if (!transactions.enabled()) return;
        try {
            for (long id : mapper.due(now())) {
                if (!slots.tryAcquire()) break;
                try { executor.execute(() -> { try { attempt(id); } finally { slots.release(); } }); }
                catch (RuntimeException error) { slots.release(); throw error; }
            }
        } catch (RuntimeException error) { unconfirmed(0); }
    }
    /** Synchronous bounded scan for operators and deterministic tests. Uses the same instance-wide slots. */
    public int runOnce() {
        maintain();
        if (!transactions.enabled()) return 0;
        int attempts = 0;
        for (long id : mapper.due(now())) if (run(id)) attempts++;
        return attempts;
    }
    public boolean run(long id) {
        if (!slots.tryAcquire()) return false;
        try { return attempt(id); } finally { slots.release(); }
    }
    private boolean attempt(long id) {
        try (var trace = MDC.putCloseable("traceId", UUID.randomUUID().toString())) {
            ProcessingRow claim = transactions.claim(id);
            if (claim == null) return false;
            TextChunker parser = null;
            try {
                var source = transactions.source(claim);
                if (source == null) return true;
                parser = new TextChunker(source.byteSize, claim.sourceSha256, System.nanoTime() + 30_000_000_000L, System::nanoTime);
                storage.read(source.location(), TextChunker.MAX_SOURCE_BYTES, parser);
                ParsedDocument result = parser.finish();
                for (int offset=0; offset<result.chunks().size(); offset+=128) {
                    parser.check();
                    claim = transactions.stage(claim, result.chunks().subList(offset, Math.min(offset+128, result.chunks().size())));
                    if (claim == null) return true;
                }
                parser.check();
                transactions.publish(claim, result);
            } catch (ProcessingFailure error) { transactions.fail(claim, error.code(), false);
            } catch (StorageFailure error) {
                if (parser != null && parser.failure() != null) transactions.fail(claim, parser.failure().code(), false);
                else if (!error.retryableRead()) transactions.fail(claim, "INTEGRITY_MISMATCH", false);
                else transactions.fail(claim, error.errorCode() == com.devmate.common.api.ErrorCode.KNOWLEDGE_STORAGE_TIMEOUT
                        ? "STORAGE_TIMEOUT" : "STORAGE_UNAVAILABLE", true);
            } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException error) {
                // A committed version change makes this update a no-op; an unavailable database retains its durable lease.
                transactions.fail(claim, "STORAGE_UNAVAILABLE", true);
            }
            return true;
        } catch (RuntimeException error) { unconfirmed(id); return false; }
    }
    private void maintain() {
        try (var trace = MDC.putCloseable("traceId", UUID.randomUUID().toString())) {
            if (!transactions.enabled()) for (long id : mapper.processingCandidates()) transactions.pauseClaim(id);
            for (long id : mapper.cleanupCandidates()) transactions.cleanup(id);
            for (long id : mapper.expiredRequests(now())) {
                var hint = mapper.findRequest(id); if (hint != null) transactions.purgeCandidate(hint);
            }
        } catch (RuntimeException error) { unconfirmed(0); }
    }
    private void unconfirmed(long id) {
        String trace = MDC.get("traceId");
        LOG.warn("Processing recovery deferred traceId={} processingId={} code=STATE_UNCONFIRMED", trace == null ? UUID.randomUUID().toString() : trace, id);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    @Override public void destroy() { executor.shutdownNow(); }
}
