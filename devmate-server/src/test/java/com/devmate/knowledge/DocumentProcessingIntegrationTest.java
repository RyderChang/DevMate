package com.devmate.knowledge;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.knowledge.application.*;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.config.ProcessingProperties;
import com.devmate.knowledge.infrastructure.DocumentMapper;
import com.devmate.knowledge.infrastructure.ProcessingMapper;
import com.devmate.knowledge.infrastructure.ProcessingRow;
import com.devmate.mapper.RoleMapper;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.service.ProjectService;
import com.devmate.security.CurrentUser;
import com.devmate.security.JwtService;
import com.devmate.service.UserRoleService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.devmate.common.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(DocumentProcessingIntegrationTest.StorageConfiguration.class)
class DocumentProcessingIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired ProcessingService service;
    @Autowired ProcessingTransactions transactions;
    @Autowired ProcessingRecovery recovery;
    @Autowired ProcessingMapper mapper;
    @Autowired DocumentMapper documents;
    @Autowired DocumentService uploads;
    @Autowired DocumentRecovery originalRecovery;
    @Autowired ProjectService projects;
    @Autowired KnowledgeProperties knowledge;
    @Autowired ProcessingProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRoleService roles;
    @Autowired RoleMapper roleMapper;
    @Autowired ProcessingStorage storage;
    @MockitoBean(name="knowledgeClock") Clock clock;
    final AtomicReference<Instant> time = new AtomicReference<>();
    long owner, project;
    String token;

    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        DocumentLifecycleIntegrationTest.knowledgeConfiguration(registry);
        registry.add("devmate.knowledge.processing.scheduling-enabled", () -> false);
    }
    @BeforeEach void setup() {
        properties.setEnabled(true); knowledge.setEnabled(true);
        jdbc.update("UPDATE knowledge_documents SET active_processing_id=NULL");
        for (String table : List.of("knowledge_chunks","knowledge_processing_requests","knowledge_processing",
                "knowledge_document_requests","knowledge_documents","knowledge_processing_capacity","knowledge_project_capacity","projects","users"))
            jdbc.update("DELETE FROM " + table);
        owner = user("processing-owner"); project = projects.create(owner,new CreateProjectRequest("Synthetic",null)).id();
        token = token(owner,"processing-owner");
        time.set(Instant.parse("2026-09-28T02:00:00Z")); when(clock.instant()).thenAnswer(ignored -> time.get());
        storage.reset(); storage.reads.set(0); storage.failRead=false; storage.blockRead=false;
    }
    @Test void explicitProcessingPublishesCompleteGenerationAndReplaysWithoutReadingAgain() throws Exception {
        long document = upload("\ufeff" + "😀中\r\n\r x\n".repeat(300)); String request=uuid();
        assertThat(service.status(owner,project,document).latest()).isNull();
        assertThat(storage.reads).hasValue(0); // Upload never schedules a processing record.
        mvc.perform(post(path(document)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                        .content(json(request))).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.latest.state").value("PENDING"))
                .andExpect(jsonPath("$.data.active").isEmpty()).andExpect(jsonPath("$.data.indexed").value(false));
        long id=latest(document).id;
        assertThat(service.start(owner,project,document,request).response().latest().processingId()).isEqualTo(id);
        assertThat(recovery.run(id)).isTrue();
        var response=service.status(owner,project,document);
        assertThat(response.latest().state()).isEqualTo("CHUNKED"); assertThat(response.active()).isEqualTo(response.latest());
        assertThat(transactions.readActive(owner,project,document,0,100)).isEqualTo(parsed(document).chunks());
        assertThat(capacity()).isEqualTo(response.latest().textBytes());
        assertThat(service.start(owner,project,document,request).accepted()).isFalse();
        assertThat(service.start(owner,project,document,uuid()).response().latest().processingId()).isEqualTo(id);
        mvc.perform(get(path(document)).header("Authorization",token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positionBasis").value("NORMALIZED_UNICODE_CODE_POINT"))
                .andExpect(jsonPath("$.data.latest.text").doesNotExist()).andExpect(jsonPath("$.data.latest.objectKey").doesNotExist());
        assertThat(storage.reads).hasValue(1); assertThat(mapper.documentRecords(document)).hasSize(1);
    }
    @Test void stagingRemainsInvisibleUntilManifestValidationAndReservationIsChargedOnlyOnce() {
        long document=upload("x".repeat(200000)); long id=start(document);
        var claim=transactions.claim(id); var parsed=parsed(document);
        claim=transactions.stage(claim,parsed.chunks().subList(0,128));
        assertThat(mapper.countChunks(id)).isEqualTo(128); assertThat(capacity()).isEqualTo(TextChunker.MAX_TEXT_BYTES);
        assertThat(transactions.readActive(owner,project,document,0,100)).isEmpty();
        assertThat(service.status(owner,project,document).active()).isNull();
        var partial=claim;
        assertCode(() -> transactions.publish(partial,parsed),INTERNAL_ERROR);
        claim=transactions.stage(claim,parsed.chunks().subList(128,parsed.chunks().size()));
        assertThat(transactions.publish(claim,parsed)).isTrue();
        assertThat(capacity()).isEqualTo(parsed.textBytes());
    }
    @Test void generationFailurePreservesAnEarlierActiveGenerationAndRequiresCleanupBeforeNewAttempt() {
        long document=upload("x".repeat(5000)); long first=start(document); recovery.run(first);
        jdbc.update("UPDATE knowledge_processing SET strategy_version='text-window-previous' WHERE id=?",first);
        String failedRequest=uuid(); long second=service.start(owner,project,document,failedRequest).response().latest().processingId();
        var claim=transactions.claim(second); var result=parsed(document);
        claim=transactions.stage(claim,result.chunks().subList(0,2)); transactions.fail(claim,"INVALID_TEXT",false);
        assertThat(service.status(owner,project,document).latest().state()).isEqualTo("FAILED");
        assertThat(service.status(owner,project,document).active().processingId()).isEqualTo(first);
        assertCode(() -> service.start(owner,project,document,uuid()),DOCUMENT_PROCESSING_IN_PROGRESS);
        assertCode(() -> service.start(owner,project,document,failedRequest),DOCUMENT_PROCESSING_TERMINATED);
        assertThat(transactions.cleanup(second)).isTrue();
        assertThat(capacity()).isEqualTo(mapper.find(first).textBytes);
        assertThat(service.start(owner,project,document,uuid()).accepted()).isTrue();
        assertThat(mapper.find(first).state).isEqualTo("CHUNKED");
    }
    @Test void successfulNewStrategyRetiresOldChunksWithoutChangingTheirIdentityAndBlocksAThirdGenerationUntilCleanup() {
        long document=upload("😀".repeat(2500)); long first=start(document); recovery.run(first);
        var oldChunks=mapper.chunks(first,0,100);
        jdbc.update("UPDATE knowledge_processing SET strategy_version='text-window-previous' WHERE id=?",first);
        long second=start(document); recovery.run(second);
        assertThat(service.status(owner,project,document).active().processingId()).isEqualTo(second);
        assertThat(mapper.chunks(first,0,100)).isEqualTo(oldChunks);
        assertThat(capacity()).isEqualTo(mapper.find(first).textBytes+mapper.find(second).textBytes);
        jdbc.update("UPDATE knowledge_processing SET strategy_version='text-window-previous' WHERE id=?",second);
        assertCode(() -> service.start(owner,project,document,uuid()),DOCUMENT_PROCESSING_IN_PROGRESS);
        assertThat(transactions.cleanup(first)).isTrue();
        assertThat(capacity()).isEqualTo(mapper.find(second).textBytes);
        assertThat(service.start(owner,project,document,uuid()).accepted()).isTrue();
    }
    @Test void leaseExpiryAndReclaimedVersionRejectLateStagingAndPublication() {
        long document=upload("x".repeat(5000)); long id=start(document); var old=transactions.claim(id); var result=parsed(document);
        old=transactions.stage(old,result.chunks().subList(0,2));
        time.updateAndGet(value -> value.plusSeconds(121)); var fresh=transactions.claim(id);
        assertThat(fresh.operationVersion).isGreaterThan(old.operationVersion); assertThat(mapper.countChunks(id)).isZero();
        assertThat(transactions.stage(old,result.chunks())).isNull(); assertThat(transactions.publish(old,result)).isFalse();
        fresh=transactions.stage(fresh,result.chunks()); assertThat(transactions.publish(fresh,result)).isTrue();
        assertThat(mapper.countChunks(id)).isEqualTo(result.chunks().size()); assertThat(capacity()).isEqualTo(result.textBytes());
    }
    @Test void restartedScannerCleansPartialAttemptBeforeRetryAndNeverDuplicatesChunks() {
        long document=upload("x".repeat(200000)); long id=start(document); var claim=transactions.claim(id); var result=parsed(document);
        claim=transactions.stage(claim,result.chunks().subList(0,128));
        claim=transactions.stage(claim,result.chunks().subList(128,200));
        time.updateAndGet(value -> value.plusSeconds(121));
        try {
            var restarted=new ProcessingRecovery(mapper,transactions,properties,storage,clock);
            try {
                assertThat(restarted.run(id)).isFalse(); assertThat(mapper.countChunks(id)).isEqualTo(72);
                assertThat(restarted.run(id)).isTrue();
            } finally { restarted.destroy(); }
        } finally { storage.release.countDown(); }
        assertThat(mapper.find(id).state).isEqualTo("CHUNKED"); assertThat(mapper.countChunks(id)).isEqualTo(result.chunks().size());
        assertThat(capacity()).isEqualTo(result.textBytes());
    }
    @Test void transientReadFailuresRetryExactlyThreeTimesWithOneTwoFourMinuteBackoff() {
        long document=upload("synthetic"); String request=uuid(); long id=service.start(owner,project,document,request).response().latest().processingId();
        storage.failRead=true;
        for (long delay : List.of(1L,2L,4L)) {
            assertThat(recovery.run(id)).isTrue(); var row=mapper.find(id);
            assertThat(row.state).isEqualTo("PENDING"); assertThat(row.nextAttemptAt.toInstant(java.time.ZoneOffset.UTC)).isEqualTo(time.get().plusSeconds(delay*60));
            assertThat(recovery.run(id)).isFalse(); time.updateAndGet(value -> value.plusSeconds(delay*60));
        }
        assertThat(recovery.run(id)).isTrue(); assertThat(mapper.find(id).state).isEqualTo("FAILED");
        assertThat(mapper.find(id).retryCount).isEqualTo(3); assertThat(storage.reads).hasValue(4);
        assertThat(mapper.find(id).attemptCount).isEqualTo(4);
        assertThat(recovery.run(id)).isFalse(); assertCode(() -> service.start(owner,project,document,request),DOCUMENT_PROCESSING_TERMINATED);
        assertThat(transactions.cleanup(id)).isTrue(); assertThat(capacity()).isZero();
        storage.failRead=false; long next=start(document); recovery.run(next); assertThat(mapper.find(next).state).isEqualTo("CHUNKED");
    }
    @Test void corruptOversizedTruncatedAndInvalidObjectsFailDeterministicallyWithoutChangingStoredOriginalMetadata() {
        for (byte[] bytes : List.of("different".getBytes(),new byte[]{(byte)0xc3,0x28},new byte[]{'x',0},new byte[12],new byte[0])) {
            long document=upload("synthetic"); var source=documents.find(document); storage.objects.put(source.location(),bytes);
            // Even when the adapter hides the sink exception, the parser's safe failure remains available.
            long id=start(document); assertThat(recovery.run(id)).isTrue(); var row=mapper.find(id);
            assertThat(row.state).isEqualTo("FAILED"); assertThat(row.errorCode).isIn("INTEGRITY_MISMATCH","INVALID_TEXT");
            assertThat(row.retryCount).isZero(); assertThat(mapper.countChunks(id)).isZero();
            assertThat(documents.find(document).storageState).isEqualTo(StorageState.STORED);
            assertThat(transactions.cleanup(id)).isTrue();
        }
    }
    @Test void changedSourceMetadataFailsTheClaimAndCannotPublishAnEarlierManifest() {
        long document=upload("synthetic"); long id=start(document); var claim=transactions.claim(id);
        jdbc.update("UPDATE knowledge_documents SET sha256=? WHERE id=?","a".repeat(64),document);
        assertThat(transactions.source(claim)).isNull();
        assertThat(mapper.find(id).state).isEqualTo("FAILED"); assertThat(mapper.find(id).errorCode).isEqualTo("INTEGRITY_MISMATCH");
        assertThat(mapper.find(id).leaseOwner).isNull(); assertThat(mapper.active(document)).isNull();
        assertThat(transactions.cleanup(id)).isTrue(); assertThat(capacity()).isZero();
    }
    @Test void unsupportedFrozenStrategyFailsWithoutApplyingTheCurrentDefaultAlgorithm() {
        long document=upload("synthetic"); long id=start(document);
        jdbc.update("UPDATE knowledge_processing SET strategy_version='unsupported-version' WHERE id=?",id);
        assertThat(recovery.run(id)).isFalse();
        assertThat(mapper.find(id).state).isEqualTo("FAILED"); assertThat(mapper.find(id).errorCode).isEqualTo("PROCESSING_VERSION_UNAVAILABLE");
        assertThat(storage.reads).hasValue(0); assertThat(transactions.cleanup(id)).isTrue(); assertThat(capacity()).isZero();
    }
    @Test void reusingAnExpiredCreatorUuidCreatesANewGenerationAndPrunesItsCleanedUnreferencedPredecessor() {
        long document=upload("synthetic"); String request=uuid();
        long old=service.start(owner,project,document,request).response().latest().processingId();
        var claim=transactions.claim(old); transactions.fail(claim,"INVALID_TEXT",false); transactions.cleanup(old);
        time.updateAndGet(value -> value.plusSeconds(24*3600+1));
        var next=service.start(owner,project,document,request);
        assertThat(next.accepted()).isTrue(); assertThat(next.response().latest().generation()).isEqualTo(2);
        assertThat(mapper.find(old)).isNull(); assertThat(mapper.documentRecords(document)).hasSize(1);
        assertThat(mapper.documentRequests(owner,project,document)).isEqualTo(1);
    }
    @Test void documentDeletionCancelsAtOnceAndProtectsParentUntilLastDerivedChunkIsCleaned() {
        long document=upload("x".repeat(200000)); long id=start(document); recovery.run(id);
        uploads.delete(owner,project,document);
        assertThat(mapper.find(id).state).isEqualTo("CANCELLED"); assertThat(mapper.active(document)).isNull();
        originalRecovery.runOnce();
        assertThat(storage.objects).isEmpty(); assertThat(documents.find(document)).isNotNull();
        assertThat(transactions.cleanup(id)).isFalse(); assertThat(documents.find(document)).isNotNull();
        assertThat(transactions.cleanup(id)).isTrue(); assertThat(capacity()).isZero();
        time.updateAndGet(value -> value.plusSeconds(61)); originalRecovery.runOnce();
        assertThat(documents.find(document)).isNull(); assertThat(mapper.find(id)).isNull();
        var mapping=jdbc.queryForMap("SELECT document_id,processing_id,terminal_state,expires_at FROM knowledge_processing_requests");
        assertThat(mapping.get("document_id")).isEqualTo(document); assertThat(mapping.get("processing_id")).isNull();
        assertThat(mapping.get("terminal_state")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT reserved_documents FROM knowledge_project_capacity WHERE project_id=?",Integer.class,project)).isZero();
    }
    @Test void projectDeletionIsSynchronousAndDeletionBeforePublicationRejectsLateWork() {
        long document=upload("x".repeat(5000)); long id=start(document); var claim=transactions.claim(id); var result=parsed(document);
        claim=transactions.stage(claim,result.chunks());
        projects.delete(owner,project);
        assertThat(mapper.find(id).state).isEqualTo("CANCELLED"); assertThat(mapper.active(document)).isNull();
        assertThat(transactions.publish(claim,result)).isFalse(); assertThat(transactions.stage(claim,result.chunks())).isNull();
        assertCode(() -> transactions.readActive(owner,project,document,0,20),DOCUMENT_NOT_FOUND);
        properties.setEnabled(false); knowledge.setEnabled(false); recovery.runOnce();
        assertThat(mapper.countChunks(id)).isZero(); assertThat(capacity()).isZero();
        assertThat(storage.objects).hasSize(1); // Remote cleanup remains behind the independent storage switch.
    }
    @Test void deletingDuringRemoteReadPreventsTheBlockedThreadFromStagingOrPublishing() throws Exception {
        long document=upload("x".repeat(5000)); long id=start(document); storage.blockRead=true;
        try (var pool=Executors.newSingleThreadExecutor()) {
            var work=pool.submit(() -> recovery.run(id));
            assertThat(storage.entered.await(5,TimeUnit.SECONDS)).isTrue();
            uploads.delete(owner,project,document); assertThat(mapper.find(id).state).isEqualTo("CANCELLED");
            recovery.runOnce(); storage.release.countDown(); assertThat(work.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(mapper.countChunks(id)).isZero(); assertThat(mapper.active(document)).isNull(); assertThat(capacity()).isZero();
        } finally { storage.release.countDown(); }
    }
    @Test void disabledProcessingPausesClaimWithANewVersionAndCanRecoverAfterRestart() {
        long document=upload("x".repeat(5000)); long id=start(document); var claim=transactions.claim(id); var result=parsed(document);
        claim=transactions.stage(claim,result.chunks().subList(0,2));
        properties.setEnabled(false); recovery.runOnce();
        assertThat(mapper.find(id).state).isEqualTo("PENDING"); assertThat(mapper.find(id).operationVersion).isGreaterThan(claim.operationVersion);
        assertThat(transactions.publish(claim,result)).isFalse(); assertCode(() -> service.start(owner,project,document,uuid()),DOCUMENT_PROCESSING_DISABLED);
        assertThat(service.status(owner,project,document).latest().state()).isEqualTo("PENDING");
        properties.setEnabled(true); assertThat(recovery.run(id)).isTrue(); assertThat(mapper.find(id).state).isEqualTo("CHUNKED");
        assertThat(capacity()).isEqualTo(result.textBytes());
    }
    @Test void sameUuidAndDifferentDocumentsConflictAndConcurrentClaimsHaveOneWinner() throws Exception {
        long document=upload("synthetic"); String request=uuid(); long id=service.start(owner,project,document,request).response().latest().processingId();
        long other=upload("other"); assertCode(() -> service.start(owner,project,other,request),DOCUMENT_PROCESSING_CONFLICT);
        assertCode(() -> service.start(owner,project,document,uuid()),DOCUMENT_PROCESSING_IN_PROGRESS);
        var barrier=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { barrier.await(); return transactions.claim(id); });
            var b=pool.submit(() -> { barrier.await(); return transactions.claim(id); }); barrier.countDown();
            var first=a.get(5,TimeUnit.SECONDS); var second=b.get(5,TimeUnit.SECONDS);
            assertThat(first==null ^ second==null).isTrue();
        }
    }
    @Test void instanceSlotsLimitReadsToTwoAndAnotherInstanceCannotStealAnUnexpiredClaim() throws Exception {
        long one=upload("one"),two=upload("two"),three=upload("three");
        long first=start(one),second=start(two),third=start(three);
        storage.blockRead=true; storage.entered=new CountDownLatch(2);
        var other=new ProcessingRecovery(mapper,transactions,properties,storage,clock);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> recovery.run(first)); var b=pool.submit(() -> recovery.run(second));
            assertThat(storage.entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(recovery.run(third)).isFalse(); assertThat(mapper.find(third).state).isEqualTo("PENDING");
            assertThat(other.run(first)).isFalse(); assertThat(storage.reads).hasValue(2);
            storage.release.countDown(); assertThat(a.get(5,TimeUnit.SECONDS)).isTrue(); assertThat(b.get(5,TimeUnit.SECONDS)).isTrue();
        } finally { storage.release.countDown(); other.destroy(); }
    }
    @Test void concurrentQuotaReservationsCannotExceedProjectLimit() throws Exception {
        long seed=upload("seed"); start(seed);
        jdbc.update("UPDATE knowledge_processing_capacity SET charged_bytes=? WHERE project_id=?",268435456L-TextChunker.MAX_TEXT_BYTES,project);
        long one=upload("one"), two=upload("two"); var barrier=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> reserveAfter(barrier,one)); var b=pool.submit(() -> reserveAfter(barrier,two)); barrier.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(capacity()).isEqualTo(268435456L);
    }
    @Test void documentRequestLimitKeepsFullCapacityReplayAndFixedExpiryWithoutRenewal() throws Exception {
        long document=upload("synthetic"); String creator=uuid(); long id=service.start(owner,project,document,creator).response().latest().processingId(); recovery.run(id);
        String binding=uuid(); service.start(owner,project,document,binding);
        var expiry=mapper.request(owner,project,binding).expiresAt;
        for (int i=0;i<98;i++) service.start(owner,project,document,uuid());
        assertThat(mapper.documentRequests(owner,project,document)).isEqualTo(100);
        assertThat(service.start(owner,project,document,creator).accepted()).isFalse();
        assertCode(() -> service.start(owner,project,document,uuid()),DOCUMENT_PROCESSING_REQUEST_LIMIT);
        time.updateAndGet(value -> value.plusSeconds(3600)); service.start(owner,project,document,binding);
        assertThat(mapper.request(owner,project,binding).expiresAt).isEqualTo(expiry);
        jdbc.update("UPDATE knowledge_processing SET strategy_version='text-window-previous' WHERE id=?",id);
        var source=documents.find(document);
        String frozen=TextChunker.sha((document+":"+source.sha256+":"+TextChunker.PARSER+":text-window-previous").getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE knowledge_processing_requests SET strategy_version='text-window-previous',fingerprint=? WHERE client_request_id=?",frozen,creator);
        assertThat(service.start(owner,project,document,creator).response().latest().strategyVersion()).isEqualTo("text-window-previous");
        assertThat(mapper.request(owner,project,creator).strategyVersion).isEqualTo("text-window-previous");
        time.updateAndGet(value -> value.plusSeconds(24*3600));
        assertCode(() -> service.start(owner,project,document,uuid()),DOCUMENT_PROCESSING_REQUEST_LIMIT);
        assertThat(mapper.expiredRequests(java.time.LocalDateTime.ofInstant(time.get(),java.time.ZoneOffset.UTC))).hasSize(20);
        for (long expired : mapper.expiredRequests(java.time.LocalDateTime.ofInstant(time.get(),java.time.ZoneOffset.UTC))) transactions.purgeCandidate(mapper.findRequest(expired));
        assertThat(mapper.projectRequests(owner,project)).isEqualTo(80); assertThat(mapper.request(owner,project,creator)).isNotNull();
    }
    @Test void projectRequestLimitIncludesDeletedParentsAndConcurrentNewBindingsCannotExceedDocumentLimit() throws Exception {
        long document=upload("synthetic"); String creator=uuid(); long id=service.start(owner,project,document,creator).response().latest().processingId(); recovery.run(id);
        for (int i=0;i<98;i++) service.start(owner,project,document,uuid());
        var barrier=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> reserveAfter(barrier,document)); var b=pool.submit(() -> reserveAfter(barrier,document)); barrier.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String number="a.n+10*b.n+100*c.n+1000*d.n";
        String sql="INSERT INTO knowledge_processing_requests(owner_user_id,project_id,document_id,client_request_id,fingerprint,generation,source_sha256,"
                + "parser_version,strategy_version,terminal_state,error_code,creator,expires_at) SELECT ?,?,999000+"+number+",LOWER(UUID()),'"+"a".repeat(64)+"',1,'"+"a".repeat(64)
                + "','utf8-text-v1','text-window-v1','CANCELLED','DOCUMENT_DELETED',1,? FROM "
                + digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d WHERE "+number+"<9900";
        jdbc.update(sql,owner,project,java.sql.Timestamp.from(time.get().minusSeconds(1))); long next=upload("next");
        assertThat(mapper.projectRequests(owner,project)).isEqualTo(10000);
        assertCode(() -> service.start(owner,project,next,uuid()),DOCUMENT_PROCESSING_REQUEST_LIMIT);
        assertThat(service.start(owner,project,document,creator).accepted()).isFalse();
    }
    @Test void mysqlConstraintsRejectInvalidOwnershipStatesPositionsDuplicateOrdinalsAndActiveGenerations() {
        long document=upload("x".repeat(5000)); long id=start(document); recovery.run(id);
        for (String mutation : List.of("state='UNKNOWN'","generation=0","retry_count=4","error_code='PRIVATE'","used_bytes=-1",
                "source_sha256='invalid'","chunk_count=8193","reserved_bytes=8388609"))
            assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_processing SET "+mutation+" WHERE id=?",id)).isInstanceOf(DataAccessException.class);
        for (String mutation : List.of("start_offset=-1","end_offset=start_offset","start_line=0","ordinal=8192","byte_size=0","owner_user_id=owner_user_id+1"))
            assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_chunks SET "+mutation+" WHERE processing_id=? AND ordinal=0",id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_chunks SET ordinal=0 WHERE processing_id=? AND ordinal=1",id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_processing_capacity SET charged_bytes=268435457 WHERE project_id=?",project)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM knowledge_documents WHERE id=?",document)).isInstanceOf(DataAccessException.class);
        String clone="INSERT INTO knowledge_processing(owner_user_id,project_id,document_id,generation,source_sha256,normalized_sha256,parser_version,strategy_version,"
                + "state,active,chunk_count,text_bytes,used_bytes,reserved_bytes,next_attempt_at,create_time,update_time) "
                + "SELECT owner_user_id,project_id,document_id,generation+1,source_sha256,normalized_sha256,parser_version,strategy_version,"
                + "'CHUNKED',1,chunk_count,text_bytes,0,0,next_attempt_at,create_time,update_time FROM knowledge_processing WHERE id=?";
        assertThatThrownBy(() -> jdbc.update(clone,id)).isInstanceOf(DataAccessException.class);
    }
    @Test void processingApiEnforcesAuthOwnershipParametersFeatureSwitchAndStoredState() throws Exception {
        long document=upload("synthetic"); String path=path(document);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json(uuid()))).andExpect(status().isUnauthorized());
        jdbc.update("INSERT INTO users(username,password) VALUES('no-role',?)",uuid());
        long noRole=jdbc.queryForObject("SELECT id FROM users WHERE username='no-role'",Long.class);
        String noAuthority=token(noRole,"no-role");
        mvc.perform(get(path).header("Authorization",noAuthority)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization",noAuthority).contentType(MediaType.APPLICATION_JSON).content(json(uuid()))).andExpect(status().isForbidden());
        long outsider=user("outsider"); String outsideToken=token(outsider,"outsider");
        mvc.perform(get(path).header("Authorization",outsideToken)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization",outsideToken).contentType(MediaType.APPLICATION_JSON).content(json(uuid()))).andExpect(status().isNotFound());
        long otherProject=projects.create(owner,new CreateProjectRequest("Other",null)).id();
        mvc.perform(get("/projects/"+otherProject+"/documents/"+document+"/processing").header("Authorization",token)).andExpect(status().isNotFound());
        for (String body : List.of("{}","{\"clientRequestId\":\"bad\"}","null","{broken"))
            mvc.perform(post(path).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        properties.setEnabled(false);
        mvc.perform(post(path).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json(uuid()))).andExpect(status().isServiceUnavailable());
        mvc.perform(get(path).header("Authorization",token)).andExpect(status().isOk());
        properties.setEnabled(true); jdbc.update("UPDATE knowledge_documents SET storage_state='UPLOADING' WHERE id=?",document);
        mvc.perform(post(path).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json(uuid()))).andExpect(status().isConflict());
        uploads.delete(owner,project,document); mvc.perform(get(path).header("Authorization",token)).andExpect(status().isNotFound());
        assertThat(storage.reads).hasValue(0);
    }
    private boolean reserveAfter(CountDownLatch barrier,long document) throws Exception {
        barrier.await(); try { service.start(owner,project,document,uuid()); return true; }
        catch (BusinessException error) { assertThat(error.getCode()).isEqualTo(409); return false; }
    }
    private long user(String name) {
        jdbc.update("INSERT INTO users(username,password) VALUES(?,?)",name,uuid());
        long id=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name); roles.assignRole(id,roleMapper.findByCode("USER")); return id;
    }
    private String token(long id,String username) { return "Bearer "+jwt.generate(new CurrentUser(id,username,List.of("USER"))); }
    private String path(long document) { return "/projects/"+project+"/documents/"+document+"/processing"; }
    private String json(String request) { return "{\"clientRequestId\":\""+request+"\"}"; }
    private String uuid() { return UUID.randomUUID().toString(); }
    private long upload(String text) { return uploads.upload(owner,project,uuid(),new MockMultipartFile("file","synthetic.md","text/plain",text.getBytes(StandardCharsets.UTF_8))).id(); }
    private long start(long document) { return service.start(owner,project,document,uuid()).response().latest().processingId(); }
    private ProcessingRow latest(long document) { return mapper.latest(document); }
    private long capacity() { return jdbc.queryForObject("SELECT charged_bytes FROM knowledge_processing_capacity WHERE project_id=?",Long.class,project); }
    private ParsedDocument parsed(long document) {
        var source=documents.find(document); var bytes=storage.objects.get(source.location());
        var parser=new TextChunker(source.byteSize,source.sha256,Long.MAX_VALUE,() -> 0); parser.write(bytes,0,bytes.length); return parser.finish();
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.getClientMessage()).isEqualTo(code.getMessage()));
    }
    @TestConfiguration static class StorageConfiguration {
        @Bean @Primary ProcessingStorage processingStorage() { return new ProcessingStorage(); }
    }
    static class ProcessingStorage extends DocumentLifecycleIntegrationTest.StubStorage {
        final AtomicInteger reads=new AtomicInteger(); volatile boolean failRead,blockRead;
        @Override public void read(ObjectLocation location,long maximum,java.io.OutputStream destination) {
            reads.incrementAndGet();
            if (failRead) throw new StorageFailure(KNOWLEDGE_STORAGE_TIMEOUT,false);
            if (blockRead) {
                // Freeze the bytes already read so deletion cannot turn this into a different test outcome.
                byte[] bytes=objects.get(location); entered.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("barrier timeout"); destination.write(bytes); }
                catch (Exception error) { throw new AssertionError(error); }
                return;
            }
            try { super.read(location,maximum,destination); }
            catch (ProcessingFailure error) { throw new StorageFailure(KNOWLEDGE_STORAGE_UNAVAILABLE,false); }
        }
    }
}
