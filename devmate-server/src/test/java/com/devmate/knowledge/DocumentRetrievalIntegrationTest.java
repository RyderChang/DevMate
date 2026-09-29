package com.devmate.knowledge;

import com.devmate.ai.embedding.*;
import com.devmate.common.exception.BusinessException;
import com.devmate.database.MySqlIntegrationTestBase;
import com.devmate.knowledge.application.*;
import com.devmate.knowledge.config.*;
import com.devmate.knowledge.dto.RetrievalRequest;
import com.devmate.knowledge.infrastructure.*;
import com.devmate.knowledge.retrieval.*;
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
class DocumentRetrievalIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired RetrievalService service;
    @Autowired RetrievalRecovery recovery;
    @Autowired RetrievalTransactions transactions;
    @Autowired IndexJournal indexes;
    @Autowired IndexService indexingService;
    @Autowired IndexRecovery indexer;
    @Autowired ProcessingService processing;
    @Autowired ProcessingRecovery parser;
    @Autowired DocumentService uploads;
    @Autowired DocumentRecovery originals;
    @Autowired ProjectService projects;
    @Autowired RetrievalProperties retrieval;
    @Autowired IndexProperties indexing;
    @Autowired KnowledgeProperties knowledge;
    @Autowired ProcessingProperties parsing;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRoleService roles;
    @Autowired RoleMapper roleMapper;
    @Autowired DocumentProcessingIntegrationTest.ProcessingStorage storage;
    @MockitoBean EmbeddingGateway model;
    @MockitoBean com.devmate.knowledge.index.VectorStore vectorWrites;
    @MockitoBean VectorSearch vectors;
    @MockitoBean(name="knowledgeClock") Clock clock;
    final AtomicReference<Instant> time=new AtomicReference<>();
    long owner,project;String token;
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry){
        DocumentLifecycleIntegrationTest.knowledgeConfiguration(registry);
        registry.add("devmate.knowledge.processing.scheduling-enabled",()->false);
        registry.add("devmate.knowledge.indexing.scheduling-enabled",()->false);
        registry.add("devmate.knowledge.retrieval.scheduling-enabled",()->false);
    }
    @BeforeEach void setup(){
        assertThat(retrieval.isEnabled()).isFalse();retrieval.setEnabled(true);indexing.setEnabled(true);knowledge.setEnabled(true);parsing.setEnabled(true);
        jdbc.update("UPDATE knowledge_documents SET active_processing_id=NULL,active_index_id=NULL");
        for(String table:List.of("knowledge_retrieval_operations","knowledge_index_requests","knowledge_index_operations","knowledge_index_points","knowledge_indexes","knowledge_index_daily_tokens","knowledge_index_capacity","knowledge_chunks","knowledge_processing_requests","knowledge_processing","knowledge_document_requests","knowledge_documents","knowledge_processing_capacity","knowledge_project_capacity","projects","users"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO knowledge_index_capacity(project_id,owner_user_id) VALUES(0,0)");
        owner=user("retrieval-owner");project=projects.create(owner,new CreateProjectRequest("Synthetic",null)).id();token=token(owner,"retrieval-owner");storage.reset();
        time.set(Instant.parse("2026-09-29T23:59:00Z"));when(clock.instant()).thenAnswer(i->time.get());
        when(model.count(anyList())).thenAnswer(i->Collections.nCopies(((List<?>)i.getArgument(0)).size(),3));
        when(model.embed(anyString(),anyList(),anyList())).thenAnswer(i->{List<float[]> results=new ArrayList<>();for(int n=0;n<((List<?>)i.getArgument(1)).size();n++){float[] value=new float[1024];value[0]=1;results.add(value);}return results;});
        when(model.ended(anyString())).thenReturn(true);when(vectorWrites.matches(any(),anyList())).thenReturn(true);
        when(vectorWrites.deleteAndVerify(any(),anyList())).thenReturn(true);when(vectors.query(anyLong(),anyLong(),anyString(),anyList(),any(),anySet(),anyInt())).thenReturn(List.of());
    }
    @AfterEach void restore(){retrieval.setEnabled(false);indexing.setEnabled(false);}
    private String uuid(){return UUID.randomUUID().toString();}
    private long user(String name){jdbc.update("INSERT INTO users(username,password) VALUES(?,?)",name,uuid());long id=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);roles.assignRole(id,roleMapper.findByCode("USER"));return id;}
    private String token(long id,String name){return "Bearer "+jwt.generate(new CurrentUser(id,name,List.of("USER")));}
    private long indexed(String text){
        long document=uploads.upload(owner,project,uuid(),new MockMultipartFile("file","synthetic.md","text/plain",text.getBytes(StandardCharsets.UTF_8))).id();
        long parsed=processing.start(owner,project,document,uuid()).response().latest().processingId();assertThat(parser.run(parsed)).isTrue();
        long index=indexingService.start(owner,project,document,uuid()).response().latest().indexId();finishIndex(index);return document;
    }
    private void finishIndex(long id){for(int n=0;n<20 && Set.of("PENDING","RUNNING").contains(indexes.find(id).state());n++)indexer.run(id);assertThat(indexes.find(id).state()).isEqualTo("SUCCEEDED");}
    private VectorCandidate candidate(long document,double score){var w=indexes.active(document);var p=indexes.points(w.id(),0,1).getFirst();return new VectorCandidate(p.id(),score,document+"/"+w.processing()+"/"+w.id()+"/"+w.spec(),0,p.sha(),w.sourceSha());}
    private long receiptCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_retrieval_operations",Long.class);}
    private String receiptState(){return jdbc.queryForObject("SELECT state FROM knowledge_retrieval_operations",String.class);}
    private long bytes(){return jdbc.queryForObject("SELECT bytes FROM knowledge_index_capacity WHERE project_id=0",Long.class);}
    private String path(){return "/projects/"+project+"/knowledge/search";}
    @Test void returnsOnlyMysqlAuthorizedChunksWithFullVersionLocationsAndStableOrdering()throws Exception{
        var frozen=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../scripts/embedding-preflight/environment.json")));
        assertThat(RetrievalService.QUERY_PREFIX).isEqualTo(frozen.path("spec").path("query_prefix").asText());
        long first=indexed("Spring transactions"),second=indexed("Java rollback");var a=candidate(first,0.8);var b=candidate(second,0.8);clearInvocations(model);
        when(vectors.query(anyLong(),anyLong(),anyString(),anyList(),any(),anySet(),anyInt())).thenReturn(List.of(b,a));
        var result=service.search(owner,project,new RetrievalRequest("事务回滚",2));
        assertThat(result.incomplete()).isFalse();assertThat(result.reason()).isEqualTo("TOP_K");assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().stream().map(h->h.pointId()).toList()).isSorted();assertThat(result.hits().stream().map(h->h.text()).toList()).containsExactlyInAnyOrder("Spring transactions","Java rollback");
        assertThat(result.hits()).allSatisfy(h->{assertThat(h.sourceSha256()).hasSize(64);assertThat(h.processingGeneration()).isEqualTo(1);assertThat(h.indexGeneration()).isEqualTo(1);assertThat(h.start()).isZero();assertThat(h.end()).isPositive();});
        verify(model).count(List.of(RetrievalService.QUERY_PREFIX+"事务回滚"));assertThat(receiptState()).isEqualTo("SUCCEEDED");assertThat(result.queryTokens()).isEqualTo(3);
        mvc.perform(post(path()).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"事务回滚\",\"topK\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.hits.length()").value(2))
            .andExpect(jsonPath("$.data.spec").value(EmbeddingSpec.ID)).andExpect(jsonPath("$.data.incomplete").value(false))
            .andExpect(jsonPath("$.data.hits[0].text").value(result.hits().getFirst().text()));
    }
    @Test void apiEnforcesIdentityOwnershipBoundsAndDefaultDisabled()throws Exception{
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\"}")).andExpect(status().isUnauthorized());
        long other=user("other");mvc.perform(post(path()).header("Authorization",token(other,"other")).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\"}")).andExpect(status().isNotFound());
        for(String body:List.of("{\"query\":\" \"}","{\"query\":\"x\",\"topK\":21}","{\"query\":\"x\",\"topK\":0}"))mvc.perform(post(path()).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        retrieval.setEnabled(false);mvc.perform(post(path()).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\"}")).andExpect(status().isServiceUnavailable());
        assertThat(receiptCount()).isZero();verifyNoInteractions(model);
    }
    @Test void noActiveSourcesAvoidsAllModelCallsAndRejectsInvalidUnicode(){
        var result=service.search(owner,project,new RetrievalRequest("synthetic",null));assertThat(result.reason()).isEqualTo("NO_ACTIVE_SOURCES");assertThat(result.hits()).isEmpty();assertThat(result.retrievalId()).isNull();
        assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("\uD800",5))).isInstanceOf(BusinessException.class);verifyNoInteractions(model);assertThat(receiptCount()).isZero();
    }
    @Test void accumulatedDeletedHitsAreDiscardedAndCandidatesAreExcludedDuringReplenishment(){
        long first=indexed("retired body"),second=indexed("current body");var a=candidate(first,0.9);var b=candidate(second,0.8);var round=new AtomicInteger();
        when(vectors.query(anyLong(),anyLong(),anyString(),anyList(),any(),anySet(),anyInt())).thenAnswer(i->{
            int n=round.incrementAndGet();Set<String> excluded=i.getArgument(5);
            if(n==1){var candidates=new ArrayList<VectorCandidate>();candidates.add(a);for(int k=0;k<99;k++)candidates.add(new VectorCandidate(uuid(),0.1,a.sourceKey(),0,a.chunkSha(),a.sourceSha()));return candidates;}
            assertThat(excluded).contains(a.pointId());if(n==2){uploads.delete(owner,project,first);return List.of(b);}assertThat((Integer)i.getArgument(6)).isEqualTo(99);return List.of();
        });
        var result=service.search(owner,project,new RetrievalRequest("current",2));assertThat(result.rounds()).isEqualTo(3);assertThat(result.inspectedPoints()).isEqualTo(101);
        assertThat(result.hits()).singleElement().satisfies(h->assertThat(h.text()).isEqualTo("current body"));assertThat(result.incomplete()).isFalse();assertThat(result.reason()).isEqualTo("EXHAUSTED");
    }
    @Test void candidateBudgetIsHardAndIncompleteCannotBeReportedAsNoContent(){
        long document=indexed("active");var sample=candidate(document,0.7);
        when(vectors.query(anyLong(),anyLong(),anyString(),anyList(),any(),anySet(),anyInt())).thenAnswer(i->{var result=new ArrayList<VectorCandidate>();for(int n=0;n<(Integer)i.getArgument(6);n++)result.add(new VectorCandidate(uuid(),0.7,sample.sourceKey(),0,sample.chunkSha(),sample.sourceSha()));return result;});
        var result=service.search(owner,project,new RetrievalRequest("active",20));assertThat(result.inspectedPoints()).isEqualTo(200);assertThat(result.rounds()).isEqualTo(2);assertThat(result.incomplete()).isTrue();assertThat(result.reason()).isEqualTo("BUDGET_EXHAUSTED");
        verify(vectors,times(2)).query(eq(owner),eq(project),eq(EmbeddingSpec.ID),anyList(),any(),anySet(),eq(100));
    }
    @Test void continuouslyChangingEligibilityStopsAtThreeRounds(){
        indexed("first");long second=indexed("second");long index=indexes.active(second).id();jdbc.update("UPDATE knowledge_documents SET active_index_id=NULL WHERE id=?",second);var round=new AtomicInteger();
        when(vectors.query(anyLong(),anyLong(),anyString(),anyList(),any(),anySet(),anyInt())).thenAnswer(i->{jdbc.update("UPDATE knowledge_documents SET active_index_id=? WHERE id=?",round.incrementAndGet()%2==1?index:null,second);return List.of();});
        var result=service.search(owner,project,new RetrievalRequest("active",5));assertThat(result.rounds()).isEqualTo(3);assertThat(result.incomplete()).isTrue();assertThat(result.reason()).isEqualTo("SOURCES_CHANGED");
    }
    @Test void replacementDuringQueryCannotReturnOldGeneration(){
        long document=indexed("synthetic");var old=candidate(document,0.9);var round=new AtomicInteger();
        when(vectors.query(anyLong(),anyLong(),anyString(),anyList(),any(),anySet(),anyInt())).thenAnswer(i->{if(round.incrementAndGet()==1){
            jdbc.update("UPDATE knowledge_processing SET parser_version='previous-fixture' WHERE id=?",indexes.active(document).processing());
            long processingId=processing.start(owner,project,document,uuid()).response().latest().processingId();assertThat(parser.run(processingId)).isTrue();
            long id=indexingService.start(owner,project,document,uuid()).response().latest().indexId();finishIndex(id);return List.of(old);
        }return List.of(candidate(document,0.8));});
        var result=service.search(owner,project,new RetrievalRequest("synthetic",1));assertThat(result.rounds()).isEqualTo(2);assertThat(result.hits()).singleElement().satisfies(h->assertThat(h.indexId()).isNotEqualTo(Long.parseLong(old.sourceKey().split("/")[2])));
    }
    @Test void exactTokenUpperBoundAndMalformedVectorsFailBeforeServingText(){
        indexed("active");clearInvocations(model);when(model.count(anyList())).thenReturn(List.of(6001));
        assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("active",1))).isInstanceOf(BusinessException.class);assertThat(receiptCount()).isZero();verify(model,never()).embed(anyString(),anyList(),anyList());
        when(model.count(anyList())).thenReturn(List.of(6000));var result=service.search(owner,project,new RetrievalRequest("active",1));assertThat(result.queryTokens()).isEqualTo(6000);
        float[] invalid=new float[1024];invalid[0]=Float.NaN;when(model.embed(anyString(),anyList(),anyList())).thenReturn(Collections.singletonList(invalid));
        assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("active",1))).isInstanceOf(BusinessException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_retrieval_operations WHERE state='FAILED'",Integer.class)).isEqualTo(1);
    }
    @Test void unknownKeepsFrozenDayAndCapacityAfterPhysicalParentDeletionUntilDurableProof(){
        long document=indexed("active");long baseline=bytes();clearInvocations(model);when(model.ended(anyString())).thenReturn(false);when(model.embed(anyString(),anyList(),anyList())).thenThrow(new EmbeddingFailure("REMOTE_UNCONFIRMED",false));
        assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("active",1))).isInstanceOf(BusinessException.class);assertThat(receiptState()).isEqualTo("UNKNOWN");assertThat(bytes()).isEqualTo(baseline+4096);
        uploads.delete(owner,project,document);for(int n=0;n<4;n++){parser.runOnce();originals.runOnce();}assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class)).isZero();projects.delete(owner,project);
        time.set(time.get().plus(Duration.ofHours(25)));recovery.runOnce();assertThat(receiptCount()).isEqualTo(1);assertThat(receiptState()).isEqualTo("UNKNOWN");assertThat(jdbc.queryForObject("SELECT utc_day FROM knowledge_retrieval_operations",LocalDate.class)).isEqualTo(LocalDate.of(2026,9,29));
        verify(model,times(1)).embed(anyString(),anyList(),anyList());when(model.ended(anyString())).thenReturn(true);time.set(time.get().plus(Duration.ofMinutes(6)));recovery.runOnce();assertThat(receiptCount()).isZero();assertThat(bytes()).isEqualTo(baseline);
    }
    @Test void concurrentQueriesShareIndexingDailyQuotaAtomically()throws Exception{
        indexed("active");clearInvocations(model);jdbc.update("UPDATE knowledge_index_daily_tokens SET tokens=1999997 WHERE project_id=?",project);long baseline=bytes();var barrier=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){Callable<Boolean> action=()->{barrier.await();try{service.search(owner,project,new RetrievalRequest("active",1));return true;}catch(BusinessException error){assertThat(error.getCode()).isEqualTo(409);return false;}};var a=executor.submit(action);var b=executor.submit(action);barrier.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        assertThat(receiptCount()).isEqualTo(1);assertThat(bytes()).isEqualTo(baseline+4096);assertThat(jdbc.queryForObject("SELECT tokens FROM knowledge_index_daily_tokens WHERE project_id=0",Long.class)).isEqualTo(6);verify(model,times(1)).embed(anyString(),anyList(),anyList());
    }
    @Test void mysqlWriteFailuresFenceSendAndRetainDispatchedAcknowledgementDebt()throws Exception{
        indexed("active");clearInvocations(model);long baseline=bytes();
        try(var administrator=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var statement=administrator.createStatement()){
            statement.execute("CREATE TRIGGER test_retrieval_send_failure BEFORE INSERT ON knowledge_retrieval_operations FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic write failure'");
            try{assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("active",1))).isInstanceOf(BusinessException.class);verify(model,never()).embed(anyString(),anyList(),anyList());assertThat(receiptCount()).isZero();assertThat(bytes()).isEqualTo(baseline);assertThat(jdbc.queryForObject("SELECT tokens FROM knowledge_index_daily_tokens WHERE project_id=0",Long.class)).isEqualTo(3);}finally{statement.execute("DROP TRIGGER test_retrieval_send_failure");}
            statement.execute("CREATE TRIGGER test_retrieval_ack_failure BEFORE UPDATE ON knowledge_retrieval_operations FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic acknowledgement failure'");
            try{assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("active",1))).isInstanceOf(BusinessException.class);assertThat(receiptState()).isEqualTo("DISPATCHED");assertThat(bytes()).isEqualTo(baseline+4096);verifyNoInteractions(vectors);}finally{statement.execute("DROP TRIGGER test_retrieval_ack_failure");}
        }
        when(model.ended(anyString())).thenReturn(false);time.set(time.get().plus(Duration.ofMinutes(8)));recovery.runOnce();assertThat(receiptState()).isEqualTo("UNKNOWN");verify(model,times(1)).embed(anyString(),anyList(),anyList());
    }
    @Test void terminalReceiptRetentionIsFixedAndIntegrityMismatchNeverReturnsBody(){
        long document=indexed("active");var good=candidate(document,0.8);var bad=new VectorCandidate(good.pointId(),good.score(),good.sourceKey(),good.ordinal(),"0".repeat(64),good.sourceSha());long baseline=bytes();
        when(vectors.query(anyLong(),anyLong(),anyString(),anyList(),any(),anySet(),anyInt())).thenReturn(List.of(bad));assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("active",1))).isInstanceOf(BusinessException.class);assertThat(receiptState()).isEqualTo("FAILED");
        time.set(time.get().plus(Duration.ofHours(23)));recovery.runOnce();assertThat(receiptCount()).isEqualTo(1);time.set(time.get().plus(Duration.ofHours(2)));recovery.runOnce();assertThat(receiptCount()).isZero();assertThat(bytes()).isEqualTo(baseline);
        assertThatThrownBy(()->jdbc.update("INSERT INTO knowledge_retrieval_operations(id,owner_user_id,project_id,spec,query_sha256,tokens,utc_day,state,accepted_at,next_check_at) VALUES(?,?,?,?,'bad',6001,'2026-09-29','invented',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uuid(),owner,project,EmbeddingSpec.ID)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void localInferenceContentionIsKnownNotSentAndDoesNotCreateUnknownDebt(){
        indexed("active");clearInvocations(model);when(model.ended(anyString())).thenReturn(false);when(model.embed(anyString(),anyList(),anyList())).thenThrow(new EmbeddingFailure("LOCAL_NOT_SENT",true));
        assertThatThrownBy(()->service.search(owner,project,new RetrievalRequest("active",1))).isInstanceOf(BusinessException.class);
        assertThat(receiptState()).isEqualTo("FAILED");verify(model,never()).ended(anyString());verifyNoInteractions(vectors);
    }
}
