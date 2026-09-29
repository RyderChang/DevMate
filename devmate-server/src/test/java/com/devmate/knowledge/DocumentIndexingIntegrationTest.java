package com.devmate.knowledge;

import com.devmate.ai.embedding.*;
import com.devmate.common.exception.BusinessException;
import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.knowledge.application.*;
import com.devmate.knowledge.config.*;
import com.devmate.knowledge.index.*;
import com.devmate.knowledge.infrastructure.*;
import com.devmate.mapper.RoleMapper;
import com.devmate.project.dto.CreateProjectRequest;
import com.devmate.project.service.ProjectService;
import com.devmate.security.*;
import com.devmate.service.UserRoleService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(DocumentProcessingIntegrationTest.StorageConfiguration.class)
class DocumentIndexingIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired IndexService service;
    @Autowired IndexTransactions transactions;
    @Autowired IndexRecovery recovery;
    @Autowired IndexJournal journal;
    @Autowired ProcessingService processing;
    @Autowired ProcessingRecovery parser;
    @Autowired ProcessingTransactions processingTransactions;
    @Autowired DocumentService uploads;
    @Autowired DocumentRecovery originals;
    @Autowired DocumentMapper documents;
    @Autowired ProcessingMapper processingMapper;
    @Autowired ProjectService projects;
    @Autowired IndexProperties indexing;
    @Autowired KnowledgeProperties knowledge;
    @Autowired ProcessingProperties parsing;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRoleService roles;
    @Autowired RoleMapper roleMapper;
    @Autowired DocumentProcessingIntegrationTest.ProcessingStorage storage;
    @MockitoBean EmbeddingGateway model;
    @MockitoBean VectorStore vectors;
    @MockitoBean(name="knowledgeClock") Clock clock;
    final AtomicReference<Instant> time=new AtomicReference<>();
    final AtomicInteger inference=new AtomicInteger();
    long owner,project;String token;
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry){
        DocumentLifecycleIntegrationTest.knowledgeConfiguration(registry);
        registry.add("devmate.knowledge.processing.scheduling-enabled",()->false);
        registry.add("devmate.knowledge.indexing.scheduling-enabled",()->false);
    }
    @BeforeEach void setup(){
        assertThat(indexing.isEnabled()).isFalse(); indexing.setEnabled(true);knowledge.setEnabled(true);parsing.setEnabled(true);
        jdbc.update("UPDATE knowledge_documents SET active_processing_id=NULL,active_index_id=NULL");
        for(String table:List.of("knowledge_index_requests","knowledge_index_operations","knowledge_index_points","knowledge_indexes","knowledge_index_daily_tokens","knowledge_index_capacity",
                "knowledge_chunks","knowledge_processing_requests","knowledge_processing","knowledge_document_requests","knowledge_documents","knowledge_processing_capacity","knowledge_project_capacity","projects","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO knowledge_index_capacity(project_id,owner_user_id) VALUES(0,0)");
        owner=user("index-owner");project=projects.create(owner,new CreateProjectRequest("Synthetic",null)).id();token=token(owner,"index-owner");
        time.set(Instant.parse("2026-09-28T23:59:00Z"));when(clock.instant()).thenAnswer(i->time.get());storage.reset();inference.set(0);
        when(model.count(anyList())).thenAnswer(i->Collections.nCopies(((List<?>)i.getArgument(0)).size(),3));
        when(model.embed(anyString(),anyList(),anyList())).thenAnswer(i->{inference.incrementAndGet();List<float[]> result=new ArrayList<>();for(int n=0;n<((List<?>)i.getArgument(1)).size();n++){float[] v=new float[1024];v[0]=1;result.add(v);}return result;});
        when(model.ended(anyString())).thenReturn(true);when(vectors.matches(any(),anyList())).thenReturn(true);when(vectors.deleteAndVerify(any(),anyList())).thenReturn(true);
    }
    @AfterEach void restore(){indexing.setEnabled(false);}
    private String uuid(){return UUID.randomUUID().toString();}
    private long user(String name){jdbc.update("INSERT INTO users(username,password) VALUES(?,?)",name,uuid());long id=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);roles.assignRole(id,roleMapper.findByCode("USER"));return id;}
    private String token(long id,String name){return "Bearer "+jwt.generate(new CurrentUser(id,name,List.of("USER")));}
    private long chunked(String text){long document=uploads.upload(owner,project,uuid(),new MockMultipartFile("file","synthetic.md","text/plain",text.getBytes(StandardCharsets.UTF_8))).id();long id=processing.start(owner,project,document,uuid()).response().latest().processingId();assertThat(parser.run(id)).isTrue();return document;}
    private long start(long document){return service.start(owner,project,document,uuid()).response().latest().indexId();}
    private void finish(long id){for(int n=0;n<100 && Set.of("PENDING","RUNNING").contains(journal.find(id).state());n++)recovery.run(id);}
    private String path(long document){return "/projects/"+project+"/documents/"+document+"/indexing";}
    private String body(String request){return "{\"clientRequestId\":\""+request+"\"}";}
    private long capacity(){return jdbc.queryForObject("SELECT points FROM knowledge_index_capacity WHERE project_id=0",Long.class);}
    @Test void rejectedBeforeInferenceDoesNotLeaveUnknownOrDisableRollbackCleanup(){
        long document=chunked("synthetic");long id=start(document);
        when(model.embed(anyString(),anyList(),anyList())).thenThrow(new EmbeddingFailure("MODEL_NOT_STARTED",true));when(model.ended(anyString())).thenReturn(false);finish(id);
        assertThat(journal.find(id).state()).isEqualTo("FAILED");assertThat(journal.operations(id)).singleElement().satisfies(op->assertThat(op.state()).isEqualTo("ENDED"));
        indexing.setEnabled(false);recovery.runOnce();assertThat(journal.find(id).cleaned()).isTrue();assertThat(capacity()).isZero();
        verify(model,never()).ended(anyString());verify(vectors,never()).upsert(any(),anyList(),anyList());
    }
    @Test void completeManifestPublishesOnlyAfterAllBatchesAndReplayDoesNotCallAgain(){
        long document=chunked("synthetic Java Spring documentation ".repeat(240));assertThat(journal.latest(document)).isNull();
        String request=uuid();long id=service.start(owner,project,document,request).response().latest().indexId();int chunks=journal.find(id).chunks();assertThat(chunks).isGreaterThan(4);
        assertThat(capacity()).isEqualTo(chunks);recovery.run(id);assertThat(service.status(owner,project,document).indexed()).isFalse();
        finish(id);assertThat(journal.find(id).state()).isEqualTo("SUCCEEDED");assertThat(journal.confirmed(id,chunks)).isTrue();
        assertThat(service.status(owner,project,document).indexed()).isTrue();int calls=inference.get();
        assertThat(processing.status(owner,project,document).indexed()).isTrue();
        assertThat(service.start(owner,project,document,request).accepted()).isFalse();assertThat(service.start(owner,project,document,uuid()).response().active().indexId()).isEqualTo(id);assertThat(inference).hasValue(calls);
        verify(vectors,atLeastOnce()).upsert(any(),anyList(),anyList());
    }
    @Test void apiRequiresAuthenticationOwnershipValidationAndExplicitFeatureEnable() throws Exception {
        long document=chunked("synthetic");mvc.perform(get(path(document))).andExpect(status().isUnauthorized());
        long other=user("other");mvc.perform(get(path(document)).header("Authorization",token(other,"other"))).andExpect(status().isNotFound());
        mvc.perform(post(path(document)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body("invalid"))).andExpect(status().isBadRequest());
        indexing.setEnabled(false);mvc.perform(post(path(document)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body(uuid()))).andExpect(status().isServiceUnavailable());
        indexing.setEnabled(true);mvc.perform(post(path(document)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body(uuid()))).andExpect(status().isAccepted());
        mvc.perform(get(path(document)).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.latest.spec").value(EmbeddingSpec.ID)).andExpect(jsonPath("$.data.latest.text").doesNotExist()).andExpect(jsonPath("$.data.latest.vector").doesNotExist());
        uploads.delete(owner,project,document);mvc.perform(get(path(document)).header("Authorization",token)).andExpect(status().isNotFound());
    }
    @Test void failedBatchNeverPublishesAndOldCompleteIndexRemainsVisible(){
        long document=chunked("synthetic");long old=start(document);finish(old);
        // Exercise publication safety with a durable same-source rebuild while the old reference stays active.
        long failed=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            var existing=journal.find(old);assertThat(journal.reserve(owner,project,1,4608,2)).isTrue();
            long id=journal.insert(owner,project,document,existing.processing(),existing.sourceSha(),existing.manifest(),existing.spec(),1,4608,2,LocalDateTime.ofInstant(time.get(),ZoneOffset.UTC));
            var source=journal.points(old,0,1).getFirst();journal.insertPoint(id,new IndexPoint(0,uuid(),source.sha(),null,false));return id;
        });
        when(model.embed(anyString(),anyList(),anyList())).thenThrow(new EmbeddingFailure("INVALID_RESPONSE",true));finish(failed);
        assertThat(journal.find(failed).state()).isEqualTo("FAILED");assertThat(service.status(owner,project,document).latest().indexId()).isEqualTo(failed);assertThat(service.status(owner,project,document).active().indexId()).isEqualTo(old);
        assertThat(journal.operations(failed)).allMatch(o->o.kind().equals("MODEL"));
    }
    @Test void vectorLostAckRetainsUnknownDebtAfterDeletionAndLateWrite(){
        long document=chunked("synthetic");long id=start(document);
        doThrow(new EmbeddingFailure("REMOTE_UNCONFIRMED",false)).when(vectors).upsert(any(),anyList(),anyList());finish(id);
        assertThat(journal.find(id).state()).isEqualTo("UNKNOWN");long reserved=capacity();assertThat(reserved).isPositive();
        uploads.delete(owner,project,document);for(int n=0;n<5;n++){parser.runOnce();originals.runOnce();}
        assertThat(documents.find(document)).isNull();assertThat(journal.find(id)).isNotNull();
        recovery.cleanup(id);assertThat(capacity()).isEqualTo(reserved);assertThat(journal.find(id).cleaned()).isFalse();
        assertThat(journal.operations(id)).anyMatch(o->o.kind().equals("VECTOR") && o.state().equals("UNKNOWN"));
        verify(vectors).deleteAndVerify(any(),anyList());
    }
    @Test void modelUnknownHasNoAutomaticReplayAndKeepsOriginalUtcDayCharge(){
        long document=chunked("synthetic");long id=start(document);
        when(model.embed(anyString(),anyList(),anyList())).thenThrow(new EmbeddingFailure("REMOTE_UNCONFIRMED",false));when(model.ended(anyString())).thenReturn(false);finish(id);
        assertThat(journal.find(id).state()).isEqualTo("UNKNOWN");long tokens=journal.find(id).tokens();assertThat(tokens).isPositive();
        time.set(time.get().plus(Duration.ofDays(1)));recovery.runOnce();recovery.runOnce();
        verify(model,times(1)).embed(anyString(),anyList(),anyList());assertThat(journal.find(id).tokens()).isEqualTo(tokens);
        assertThat(jdbc.queryForObject("SELECT tokens FROM knowledge_index_daily_tokens WHERE project_id=0 AND utc_day='2026-09-28'",Long.class)).isEqualTo(tokens);
        assertThat(capacity()).isPositive();
    }
    @Test void restartAfterDispatchedOperationBecomesUnknownAndRejectsExpiredLease(){
        long document=chunked("synthetic");long id=start(document);recovery.run(id);var claim=transactions.claim(id);
        var op=transactions.begin(claim,"MODEL",journal.points(id,0,1));assertThat(op).isNotNull();
        time.set(time.get().plus(Duration.ofMinutes(8)));assertThat(transactions.claim(id)).isNull();assertThat(journal.find(id).state()).isEqualTo("UNKNOWN");
        assertThat(transactions.acknowledge(claim,op)).isFalse();assertThat(transactions.publish(claim)).isFalse();assertThat(service.status(owner,project,document).indexed()).isFalse();
    }
    @Test void deletionDuringInferenceFencesLateResultsAndHandsOffBeforeRemovingChunks(){
        long document=chunked("synthetic");long id=start(document);recovery.run(id);
        when(model.embed(anyString(),anyList(),anyList())).thenAnswer(i->{uploads.delete(owner,project,document);float[] v=new float[1024];v[0]=1;return List.of(v);});recovery.run(id);
        assertThat(journal.find(id).state()).isEqualTo("CANCELLED");assertThat(journal.active(document)).isNull();verify(vectors,never()).upsert(any(),anyList(),anyList());
        for(int n=0;n<5;n++){parser.runOnce();originals.runOnce();}assertThat(documents.find(document)).isNull();
        recovery.cleanup(id);assertThat(journal.find(id).cleaned()).isTrue();assertThat(capacity()).isZero();
    }
    @Test void requestLimitConcurrentInsertionAndFullCapacityReplayRemainBounded() throws Exception {
        long document=chunked("synthetic");String request=uuid();long id=service.start(owner,project,document,request).response().latest().indexId();finish(id);
        for(int n=0;n<98;n++)service.start(owner,project,document,uuid());
        var barrier=new CountDownLatch(1);try(var executor=Executors.newFixedThreadPool(2)){
            Callable<Boolean> action=()->{barrier.await();try{service.start(owner,project,document,uuid());return true;}catch(BusinessException failure){assertThat(failure.getCode()).isEqualTo(409);return false;}};
            var a=executor.submit(action);var b=executor.submit(action);barrier.countDown();assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(journal.requestCount(owner,project,document)).isEqualTo(100);assertThat(service.start(owner,project,document,request).response().latest().indexId()).isEqualTo(id);
    }
    @Test void mappingWindowDoesNotExtendAndDeletedParentsStillOccupyProjectQuota(){
        long document=chunked("synthetic");String original=uuid();long id=service.start(owner,project,document,original).response().latest().indexId();finish(id);String alias=uuid();service.start(owner,project,document,alias);
        LocalDateTime expires=jdbc.queryForObject("SELECT expires_at FROM knowledge_index_requests WHERE client_request_id=?",LocalDateTime.class,alias);
        time.set(time.get().plus(Duration.ofHours(1)));service.start(owner,project,document,alias);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM knowledge_index_requests WHERE client_request_id=?",LocalDateTime.class,alias)).isEqualTo(expires);
        uploads.delete(owner,project,document);for(int n=0;n<5;n++){parser.runOnce();originals.runOnce();}recovery.cleanup(id);
        assertThat(journal.requestCount(owner,project,null)).isEqualTo(2);assertThat(capacity()).isZero();time.set(time.get().plus(Duration.ofHours(25)));transactions.maintain();assertThat(journal.requestCount(owner,project,null)).isZero();
    }
    @Test void tokenBudgetFailureRollsBackGlobalReservationAndNeverSends(){
        long document=chunked("synthetic");long id=start(document);recovery.run(id);
        jdbc.update("INSERT INTO knowledge_index_daily_tokens(project_id,utc_day,tokens) VALUES(?,'2026-09-28',2000000)",project);finish(id);
        assertThat(journal.find(id).state()).isEqualTo("FAILED");verify(model,never()).embed(anyString(),anyList(),anyList());
        assertThat(journal.find(id).tokens()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_daily_tokens WHERE project_id=0",Integer.class)).isZero();
    }
    @Test void exactCountAndEmptyInputFailuresTerminateWholeGeneration(){
        long document=chunked("synthetic");long id=start(document);when(model.count(anyList())).thenReturn(List.of(6001));finish(id);
        assertThat(journal.find(id).state()).isEqualTo("FAILED");verify(model,never()).embed(anyString(),anyList(),anyList());
        when(model.count(anyList())).thenAnswer(i->Collections.nCopies(((List<?>)i.getArgument(0)).size(),3));
        long blank=chunked("x\n"+" ".repeat(4000)+"\nx");long blankIndex=start(blank);finish(blankIndex);assertThat(journal.find(blankIndex).state()).isEqualTo("FAILED");assertThat(journal.find(blankIndex).error()).isEqualTo("INVALID_INPUT");
    }
    @Test void payloadSameCountButWrongOrdinalCannotPublishAndSchemaRejectsInvalidState(){
        long document=chunked("synthetic");long id=start(document);when(vectors.matches(any(),anyList())).thenReturn(false);finish(id);
        assertThat(journal.find(id).state()).isEqualTo("FAILED");assertThat(service.status(owner,project,document).indexed()).isFalse();
        assertThatThrownBy(()->jdbc.update("UPDATE knowledge_indexes SET state='invented' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE knowledge_index_capacity SET points=5000001 WHERE project_id=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void projectMappingQuotaIncludesDeletedParentsAndStillPermitsExistingReplay(){
        long retired=chunked("retired");uploads.delete(owner,project,retired);for(int n=0;n<4;n++){parser.runOnce();originals.runOnce();}
        long document=chunked("active");String request=uuid();long id=service.start(owner,project,document,request).response().latest().indexId();finish(id);
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        jdbc.update("INSERT INTO knowledge_index_requests(owner_user_id,project_id,document_id,client_request_id,index_id,fingerprint,creator,accepted_at,expires_at,snapshot) "
                + "SELECT ?,?,?,UUID(),NULL,?,0,?,?,JSON_OBJECT('state','CANCELLED') FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d LIMIT 9999",
                owner,project,retired,"a".repeat(64),LocalDateTime.ofInstant(time.get(),ZoneOffset.UTC),LocalDateTime.ofInstant(time.get(),ZoneOffset.UTC).plusHours(24));
        assertThat(journal.requestCount(owner,project,null)).isEqualTo(10000);assertThatThrownBy(()->service.start(owner,project,document,uuid())).isInstanceOf(BusinessException.class);
        assertThat(service.start(owner,project,document,request).response().active().indexId()).isEqualTo(id);
    }
    @Test void concurrentTokenReservationIsAtomicAcrossWorks() throws Exception {
        long a=start(chunked("first")),b=start(chunked("second"));recovery.run(a);recovery.run(b);
        var first=transactions.claim(a);var second=transactions.claim(b);
        jdbc.update("INSERT INTO knowledge_index_daily_tokens(project_id,utc_day,tokens) VALUES(?,'2026-09-28',1999997)",project);
        var barrier=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var x=executor.submit(()->reserveAfter(barrier,first));var y=executor.submit(()->reserveAfter(barrier,second));barrier.countDown();
            assertThat(List.of(x.get(5,TimeUnit.SECONDS),y.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT tokens FROM knowledge_index_daily_tokens WHERE project_id=0",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT tokens FROM knowledge_index_daily_tokens WHERE project_id=?",Long.class,project)).isEqualTo(2000000);
    }
    private boolean reserveAfter(CountDownLatch barrier,IndexWork claim) throws Exception {
        barrier.await();try{return transactions.begin(claim,"MODEL",journal.points(claim.id(),0,1))!=null;}catch(BusinessException failure){assertThat(failure.getCode()).isEqualTo(409);return false;}
    }
    @Test void deletionDuringCountAndFinalVectorVerificationNeverPublishes(){
        long counted=chunked("count window");long first=start(counted);
        when(model.count(anyList())).thenAnswer(i->{uploads.delete(owner,project,counted);return List.of(3);});recovery.run(first);
        assertThat(journal.find(first).state()).isEqualTo("CANCELLED");verify(model,never()).embed(anyString(),anyList(),anyList());
        when(model.count(anyList())).thenAnswer(i->Collections.nCopies(((List<?>)i.getArgument(0)).size(),3));
        long document=chunked("publish window");long id=start(document);
        when(vectors.matches(any(),anyList())).thenAnswer(i->{projects.delete(owner,project);return true;});finish(id);
        assertThat(journal.find(id).state()).isEqualTo("CANCELLED");assertThat(journal.active(document)).isNull();assertThat(capacity()).isPositive();
    }
    @Test void processingReplacementCancelsIndexWorkAndPreservesIndependentLocation(){
        long document=chunked("synthetic");long id=start(document);recovery.run(id);
        when(model.embed(anyString(),anyList(),anyList())).thenAnswer(i->{
            jdbc.update("UPDATE knowledge_processing SET parser_version='previous-fixture' WHERE id=?",journal.find(id).processing());
            long replacement=processing.start(owner,project,document,uuid()).response().latest().processingId();assertThat(parser.run(replacement)).isTrue();
            float[] value=new float[1024];value[0]=1;return List.of(value);
        });recovery.run(id);
        assertThat(journal.find(id).state()).isEqualTo("CANCELLED");assertThat(journal.points(id,0,4)).hasSize(1);assertThat(journal.operations(id)).hasSize(1);
        assertThat(service.status(owner,project,document).indexed()).isFalse();verify(vectors,never()).upsert(any(),anyList(),anyList());
    }
    @Test void expiredAliasCanBeReboundWithoutDuplicateKeyOrExtendingAnExistingWindow(){
        long document=chunked("synthetic");long id=start(document);finish(id);String alias=uuid();service.start(owner,project,document,alias);
        LocalDateTime accepted=jdbc.queryForObject("SELECT accepted_at FROM knowledge_index_requests WHERE client_request_id=?",LocalDateTime.class,alias);
        time.set(time.get().plus(Duration.ofHours(25)));assertThat(service.start(owner,project,document,alias).response().active().indexId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT accepted_at FROM knowledge_index_requests WHERE client_request_id=?",LocalDateTime.class,alias)).isEqualTo(accepted.plusHours(25));
        assertThat(journal.requestCount(owner,project,document)).isEqualTo(2);
    }
    @Test void mysqlWriteFailureBeforeSendRollsBackAndAfterSendRetainsDispatchedDebt() throws Exception {
        long document=chunked("synthetic"),id=start(document);recovery.run(id);
        // Only the isolated container administrator installs fault injection; application privileges stay unchanged.
        try(var administrator=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var statement=administrator.createStatement()){
        statement.execute("CREATE TRIGGER test_index_insert_failure BEFORE INSERT ON knowledge_index_operations FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic write failure'");
        try{
            assertThat(recovery.run(id)).isTrue();verify(model,never()).embed(anyString(),anyList(),anyList());
            assertThat(journal.operations(id)).isEmpty();assertThat(journal.find(id).tokens()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_daily_tokens",Integer.class)).isZero();
        }finally{statement.execute("DROP TRIGGER test_index_insert_failure");}
        time.set(time.get().plus(Duration.ofMinutes(8)));
        statement.execute("CREATE TRIGGER test_index_ack_failure BEFORE UPDATE ON knowledge_index_operations FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic acknowledgement failure'");
        try{
            assertThat(recovery.run(id)).isTrue();assertThat(inference).hasValue(1);
            assertThat(journal.operations(id)).singleElement().satisfies(op->assertThat(op.state()).isEqualTo("DISPATCHED"));
            assertThat(journal.find(id).tokens()).isEqualTo(3);verify(vectors,never()).upsert(any(),anyList(),anyList());
        }finally{statement.execute("DROP TRIGGER test_index_ack_failure");}
        time.set(time.get().plus(Duration.ofMinutes(8)));recovery.run(id);
        assertThat(journal.find(id).state()).isEqualTo("UNKNOWN");assertThat(service.status(owner,project,document).indexed()).isFalse();
        assertThat(inference).hasValue(1);assertThat(capacity()).isEqualTo(1);
        }
    }
}
