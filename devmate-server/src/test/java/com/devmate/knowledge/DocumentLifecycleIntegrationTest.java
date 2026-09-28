package com.devmate.knowledge;

import com.devmate.common.exception.BusinessException;
import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.knowledge.application.DocumentRecovery;
import com.devmate.knowledge.application.DocumentService;
import com.devmate.knowledge.application.DocumentTransactions;
import com.devmate.knowledge.application.ObjectLocation;
import com.devmate.knowledge.application.ObjectStorage;
import com.devmate.knowledge.application.StorageFailure;
import com.devmate.knowledge.application.Verification;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.service.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.devmate.common.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Import(DocumentLifecycleIntegrationTest.StorageConfiguration.class)
class DocumentLifecycleIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired DocumentService service;
    @Autowired DocumentRecovery recovery;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubStorage storage;
    @Autowired com.devmate.knowledge.infrastructure.DocumentMapper mapper;
    @Autowired com.devmate.knowledge.infrastructure.UploadTempFiles temporary;
    @Autowired KnowledgeProperties properties;
    @MockitoBean(name = "knowledgeClock") Clock clock;
    @MockitoSpyBean DocumentTransactions transactions;
    final AtomicReference<Instant> time = new AtomicReference<>();
    long owner;
    long project;

    @DynamicPropertySource
    static void knowledgeConfiguration(DynamicPropertyRegistry registry) {
        registry.add("devmate.knowledge.enabled", () -> true);
        registry.add("devmate.knowledge.allow-local-http", () -> true);
        registry.add("devmate.knowledge.endpoint", () -> "http://127.0.0.1:1");
        registry.add("devmate.knowledge.bucket", () -> "synthetic-documents");
        registry.add("devmate.knowledge.access-key", () -> UUID.randomUUID().toString());
        registry.add("devmate.knowledge.secret-key", () -> UUID.randomUUID().toString());
        registry.add("devmate.knowledge.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM knowledge_document_requests");
        jdbc.update("DELETE FROM knowledge_documents");
        jdbc.update("DELETE FROM knowledge_project_capacity");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(username,password) VALUES (?,?)", "synthetic-owner", UUID.randomUUID().toString());
        owner = jdbc.queryForObject("SELECT id FROM users WHERE username='synthetic-owner'", Long.class);
        project = projects.create(owner, new CreateProjectRequest("Synthetic", null)).id();
        time.set(Instant.parse("2026-09-28T02:00:00Z"));
        when(clock.instant()).thenAnswer(ignored -> time.get());
        storage.reset();
        properties.setEnabled(true);
        properties.setMaxDocuments(100);
        properties.setMaxProjectBytes(100L * 1024 * 1024);
    }

    @Test
    void aLostStorageResponseIsReconciledWithoutAnotherPut() {
        storage.mode = "TIMEOUT_AFTER_WRITE";
        String request = uuid();
        assertCode(() -> upload(request), KNOWLEDGE_STORAGE_TIMEOUT);
        assertCode(() -> upload(request), DOCUMENT_REQUEST_IN_PROGRESS);
        assertState("UPLOADING");
        assertCapacity(1, 7);
        time.set(time.get().plus(Duration.ofMinutes(3)));
        recovery.runOnce();
        assertState("STORED");
        assertThat(upload(request).storageState()).isEqualTo("STORED");
        assertThat(storage.writes.get()).isOne();
        assertCapacity(1, 7);
    }

    @Test
    void missingHeadAndExpiredLeaseNeverReleaseAnUncertainWrite() {
        storage.mode = "TIMEOUT_BEFORE_WRITE";
        assertCode(() -> upload(uuid()), KNOWLEDGE_STORAGE_TIMEOUT);
        time.set(time.get().plus(Duration.ofMinutes(3)));
        for (int minutes : new int[]{1, 2, 4, 8, 16, 32}) {
            recovery.runOnce();
            assertState("UPLOADING");
            assertCapacity(1, 7);
            time.set(time.get().plus(Duration.ofMinutes(minutes)));
        }
        assertThat(jdbc.queryForObject("SELECT needs_manual FROM knowledge_documents", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT object_key FROM knowledge_documents", String.class)).isNotBlank();
        assertThat(storage.deletes.get()).isZero();
    }

    @Test
    void aLatePutAfterDocumentDeletionIsCleanedAndCannotResurrectTheDocument() throws Exception {
        storage.mode = "BLOCK_BEFORE_WRITE";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var upload = executor.submit(() -> upload(uuid()));
            assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();
            long id = documentId();
            service.delete(owner, project, id);
            assertCode(() -> service.get(owner, project, id), DOCUMENT_NOT_FOUND);
            time.set(time.get().plus(Duration.ofMinutes(3)));
            recovery.runOnce();
            assertState("DELETE_PENDING");
            assertCapacity(1, 7);
            assertThat(storage.deletes.get()).isZero();
            storage.release.countDown();
            assertThatThrownBy(() -> upload.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            recovery.runOnce();
            assertThat(storage.objects).isEmpty();
            assertThat(documentCount()).isZero();
            assertCapacity(0, 0);
        } finally {
            storage.release.countDown();
        }
    }

    @Test
    void softDeletingAProjectHandsStoredObjectsToDurableCleanup() {
        var response = upload(uuid());
        projects.delete(owner, project);
        assertCode(() -> service.get(owner, project, response.id()), PROJECT_NOT_FOUND);
        recovery.runOnce();
        assertThat(storage.objects).isEmpty();
        assertThat(documentCount()).isZero();
        assertCapacity(0, 0);
    }

    @Test
    void aProjectDeletedDuringPutCannotCommitStored() throws Exception {
        storage.mode = "BLOCK_BEFORE_WRITE";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> upload(uuid()));
            assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();
            projects.delete(owner, project);
            storage.release.countDown();
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertState("DELETE_PENDING");
            recovery.runOnce();
            assertThat(storage.objects).isEmpty();
            assertCapacity(0, 0);
        } finally { storage.release.countDown(); }
    }

    @Test
    void uncertainDatabaseCommitAfterPutRecoversFromThePersistedLocator() {
        doThrow(new DataAccessResourceFailureException("synthetic commit uncertainty"))
                .when(transactions).finishUpload(anyLong(), anyString(), anyBoolean());
        String request = uuid();
        assertCode(() -> upload(request), KNOWLEDGE_DATABASE_UNAVAILABLE);
        assertState("UPLOADING");
        assertCapacity(1, 7);
        time.set(time.get().plus(Duration.ofMinutes(3)));
        recovery.runOnce();
        assertThat(upload(request).storageState()).isEqualTo("STORED");
        assertThat(storage.writes.get()).isOne();
    }

    @Test
    void aPostCommitDatabaseFailureReturnsNoFalseSuccessAndReplayReadsTheCommit() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { throw new DataAccessResourceFailureException("synthetic lost acknowledgement"); }
            });
            return result;
        }).when(transactions).finishUpload(anyLong(), anyString(), anyBoolean());
        String request = uuid();
        assertCode(() -> upload(request), KNOWLEDGE_DATABASE_UNAVAILABLE);
        assertState("STORED");
        assertThat(upload(request).storageState()).isEqualTo("STORED");
        assertThat(storage.writes.get()).isOne();
    }

    @Test
    void aCommittedReservationWithoutAStartedPutCanBeSafelyFailedAndCleaned() {
        doThrow(new DataAccessResourceFailureException("synthetic pre-put commit uncertainty"))
                .when(transactions).startPut(anyLong(), anyLong(), anyString());
        String request = uuid();
        assertCode(() -> upload(request), KNOWLEDGE_DATABASE_UNAVAILABLE);
        assertThat(storage.writes.get()).isZero();
        time.set(time.get().plus(Duration.ofMinutes(3)));
        recovery.runOnce();
        assertThat(documentCount()).isZero();
        assertCapacity(0, 0);
        assertCode(() -> upload(request), DOCUMENT_REQUEST_TERMINATED);
    }

    @Test
    void deletedRequestMappingsRemainFor24HoursAndThenPermitNewIngestion() {
        String request = uuid();
        long id = upload(request).id();
        service.delete(owner, project, id);
        service.delete(owner, project, id);
        recovery.runOnce();
        assertCode(() -> upload(request), DOCUMENT_REQUEST_TERMINATED);
        assertCode(() -> service.delete(owner, project, id), DOCUMENT_NOT_FOUND);
        time.set(time.get().plus(Duration.ofHours(24)).minusMillis(1));
        assertCode(() -> upload(request), DOCUMENT_REQUEST_TERMINATED);
        time.set(time.get().plusMillis(1));
        assertThat(upload(request).id()).isNotEqualTo(id);
        assertThat(storage.writes.get()).isEqualTo(2);
    }

    @Test
    void disabledStorageStillAllowsMetadataAndDeletionAndResumeUsesPersistedWork() {
        long id = upload(uuid()).id();
        properties.setEnabled(false);
        assertThat(service.get(owner, project, id).storageState()).isEqualTo("STORED");
        service.delete(owner, project, id);
        assertCode(() -> upload(uuid()), KNOWLEDGE_SERVICE_DISABLED);
        recovery.runOnce();
        assertCapacity(1, 7);
        assertThat(storage.deletes.get()).isZero();
        properties.setEnabled(true);
        restartedRecovery().runOnce();
        assertCapacity(0, 0);
    }

    @Test
    void concurrentSameUuidHasOneReservationAndOnePut() throws Exception {
        storage.mode = "BLOCK_BEFORE_WRITE";
        String request = uuid();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> upload(request));
            assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertCode(() -> upload(request), DOCUMENT_REQUEST_IN_PROGRESS);
            assertCode(() -> service.upload(owner, project, request,
                    new MockMultipartFile("file", "other.txt", "text/plain", "content".getBytes())), DOCUMENT_REQUEST_CONFLICT);
            assertCapacity(1, 7);
            storage.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).storageState()).isEqualTo("STORED");
            assertThat(storage.writes.get()).isOne();
        } finally { storage.release.countDown(); }
    }

    @Test
    void independentConcurrentRequestsCannotOversubscribeProjectBytes() throws Exception {
        properties.setMaxProjectBytes(10);
        storage.mode = "BLOCK_BEFORE_WRITE";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> upload(uuid()));
            assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertCode(() -> upload(uuid()), DOCUMENT_CAPACITY_EXCEEDED);
            assertCapacity(1, 7);
            storage.release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally { storage.release.countDown(); }
    }

    @Test
    void twoScannerInstancesCannotBothClaimTheSameDeletion() throws Exception {
        long id = upload(uuid()).id();
        service.delete(owner, project, id);
        storage.blockDelete = true;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(recovery::runOnce);
            assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();
            restartedRecovery().runOnce();
            assertThat(storage.deletes.get()).isOne();
            storage.release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertCapacity(0, 0);
        } finally { storage.release.countDown(); }
    }

    @Test
    void aFailedTerminalCannotBecomeStoredEvenIfTheObjectIsLaterCorrected() throws Exception {
        storage.mode = "CORRUPT_AFTER_WRITE";
        String request = uuid();
        assertCode(() -> upload(request), KNOWLEDGE_STORAGE_UNAVAILABLE);
        time.set(time.get().plus(Duration.ofMinutes(3))); storage.blockDelete = true;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scanner = executor.submit(recovery::runOnce);
            assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertState("FAILED");
            storage.objects.replaceAll((location, bytes) -> "content".getBytes());
            assertCode(() -> upload(request), DOCUMENT_REQUEST_TERMINATED);
            storage.release.countDown(); scanner.get(10, TimeUnit.SECONDS);
            assertCapacity(0, 0);
            assertCode(() -> upload(request), DOCUMENT_REQUEST_TERMINATED);
            assertThat(jdbc.queryForObject("SELECT terminal_state FROM knowledge_document_requests", String.class)).isEqualTo("FAILED");
        } finally { storage.release.countDown(); }
    }

    @Test
    void aDeletionFailureHasExactBackoffAndOperatorRetryDoesNotChangeTheTerminal() throws Exception {
        String request = uuid(); long id = upload(request).id(); service.delete(owner, project, id);
        storage.rejectDelete = true;
        for (int minutes : new int[]{1,2,4,8,16}) {
            recovery.runOnce();
            var next = jdbc.queryForObject("SELECT next_attempt_at FROM knowledge_documents", java.sql.Timestamp.class).toInstant();
            assertThat(next).isEqualTo(time.get().plus(Duration.ofMinutes(minutes)));
            assertThat(recovery.runOnce()).isZero(); time.set(next);
        }
        recovery.runOnce();
        assertThat(jdbc.queryForObject("SELECT needs_manual FROM knowledge_documents", Boolean.class)).isTrue();
        assertCapacity(1, 7); assertCode(() -> upload(request), DOCUMENT_REQUEST_TERMINATED);
        String script = Files.readString(Path.of("../scripts/retry-knowledge-document.sql")).replaceAll("(?m)^--.*$", "");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET @devmate_document_id=" + id);
                for (String sql : script.split(";")) if (!sql.isBlank()) statement.execute(sql);
            }
            return null;
        });
        assertState("DELETE_PENDING"); storage.rejectDelete = false;
        // The operator script schedules with the database UTC clock, not the test clock.
        time.set(jdbc.queryForObject("SELECT next_attempt_at FROM knowledge_documents", java.sql.Timestamp.class).toInstant());
        recovery.runOnce(); assertCapacity(0, 0); assertCode(() -> upload(request), DOCUMENT_REQUEST_TERMINATED);
    }

    @Test
    void scannerClaimsAtMost50AndProjectCountCapacityIncludesUncleanedFailures() {
        storage.mode = "REJECT_BEFORE_WRITE";
        for (int i = 0; i < 100; i++) assertCode(() -> upload(uuid()), KNOWLEDGE_STORAGE_UNAVAILABLE);
        assertCapacity(100, 700);
        assertThat(service.list(owner, project, 1, 100).items()).allSatisfy(document -> {
            assertThat(document.storageState()).isEqualTo("FAILED");
            assertThat(document.failureCode()).isEqualTo("UPLOAD_ABORTED_OR_INVALID");
        });
        assertCode(() -> upload(uuid()), DOCUMENT_CAPACITY_EXCEEDED);
        assertThat(recovery.runOnce()).isEqualTo(50); assertThat(documentCount()).isEqualTo(50);
        assertCapacity(50, 350);
        assertThat(recovery.runOnce()).isEqualTo(50); assertCapacity(0, 0);
    }

    @Test
    void mysqlRejectsInvalidStatesCountsDigestsAndMismatchedOwnership() {
        long id = upload(uuid()).id();
        for (String mutation : java.util.List.of("storage_state='UNKNOWN'", "remote_phase='UNKNOWN'", "byte_size=-1", "retry_count=6", "needs_manual=2", "sha256='invalid'", "error_code='UNKNOWN'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_documents SET " + mutation + " WHERE id=?", id))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_project_capacity SET reserved_documents=101 WHERE project_id=?", project))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_project_capacity SET reserved_bytes=-1 WHERE project_id=?", project))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("INSERT INTO users(username,password) VALUES (?,?)", "synthetic-outsider", uuid());
        long outsider = jdbc.queryForObject("SELECT id FROM users WHERE username='synthetic-outsider'", Long.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_documents SET owner_user_id=? WHERE id=?", outsider, id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_document_requests SET owner_user_id=? WHERE document_id=?", outsider, id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='knowledge_documents' AND index_name='idx_knowledge_document_list' ORDER BY seq_in_index", String.class))
                .containsExactly("owner_user_id", "project_id", "create_time", "id", "storage_state");
        assertState("STORED"); assertCapacity(1, 7);
    }

    private com.devmate.knowledge.vo.DocumentResponse upload(String request) {
        return service.upload(owner, project, request,
                new MockMultipartFile("file", "note.txt", "text/plain", "content".getBytes()));
    }
    private DocumentRecovery restartedRecovery() {
        return new DocumentRecovery(mapper, transactions, projects, storage, temporary, properties, clock);
    }
    private String uuid() { return UUID.randomUUID().toString(); }
    private long documentId() { return jdbc.queryForObject("SELECT id FROM knowledge_documents", Long.class); }
    private int documentCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents", Integer.class); }
    private void assertState(String value) { assertThat(jdbc.queryForObject("SELECT storage_state FROM knowledge_documents", String.class)).isEqualTo(value); }
    private void assertCapacity(int count, long bytes) {
        assertThat(jdbc.queryForObject("SELECT reserved_documents FROM knowledge_project_capacity WHERE project_id=?", Integer.class, project)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM knowledge_project_capacity WHERE project_id=?", Long.class, project)).isEqualTo(bytes);
    }
    private void assertCode(ThrowingCallable call, com.devmate.common.api.ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getClientMessage()).isEqualTo(code.getMessage()));
    }

    @TestConfiguration
    static class StorageConfiguration {
        @Bean @Primary StubStorage stubStorage() { return new StubStorage(); }
    }

    static class StubStorage implements ObjectStorage {
        final Map<ObjectLocation, byte[]> objects = new ConcurrentHashMap<>();
        final Map<ObjectLocation, String> tokens = new ConcurrentHashMap<>();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        final AtomicInteger inspections = new AtomicInteger();
        volatile String mode = "NORMAL";
        volatile boolean blockDelete;
        volatile boolean rejectDelete;
        CountDownLatch entered;
        CountDownLatch release;
        void reset() {
            objects.clear(); tokens.clear(); writes.set(0); deletes.set(0); inspections.set(0); mode = "NORMAL"; blockDelete = false; rejectDelete = false;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
        }
        @Override public void put(ObjectLocation location, Path path, long length, String sha256, String token) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            writes.incrementAndGet();
            if (mode.equals("BLOCK_BEFORE_WRITE")) awaitBarrier();
            if (mode.equals("TIMEOUT_BEFORE_WRITE")) throw new StorageFailure(KNOWLEDGE_STORAGE_TIMEOUT, false);
            if (mode.equals("REJECT_BEFORE_WRITE")) throw new StorageFailure(KNOWLEDGE_STORAGE_UNAVAILABLE, true);
            try { objects.put(location, Files.readAllBytes(path)); tokens.put(location, token); }
            catch (Exception error) { throw new AssertionError(error); }
            if (mode.equals("TIMEOUT_AFTER_WRITE")) throw new StorageFailure(KNOWLEDGE_STORAGE_TIMEOUT, false);
            if (mode.equals("CORRUPT_AFTER_WRITE")) {
                objects.put(location, "corrupt".getBytes()); throw new StorageFailure(KNOWLEDGE_STORAGE_UNAVAILABLE, false);
            }
        }
        @Override public Verification inspect(ObjectLocation location, long length, String sha256, String token) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            inspections.incrementAndGet();
            byte[] value = objects.get(location);
            try {
                return value == null ? Verification.missing() : new Verification(true, token.equals(tokens.get(location)), value.length == length
                        && sha256.equals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value))));
            } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
        }
        @Override public void delete(ObjectLocation location) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            deletes.incrementAndGet();
            if (rejectDelete) throw new StorageFailure(KNOWLEDGE_STORAGE_UNAVAILABLE, false);
            if (blockDelete) awaitBarrier();
            objects.remove(location); tokens.remove(location);
        }
        @Override public void read(ObjectLocation location, long maximum, java.io.OutputStream destination) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try {
                byte[] value = objects.get(location);
                if (value == null || value.length > maximum) throw new StorageFailure(KNOWLEDGE_STORAGE_UNAVAILABLE, false);
                destination.write(value);
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
        private void awaitBarrier() {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
    }
}
