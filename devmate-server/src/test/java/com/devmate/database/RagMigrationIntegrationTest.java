package com.devmate.database;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class RagMigrationIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    @Test void cleanMysqlHasRagConstraintsAndProvenanceHasNoDeletableSourceForeignKeys() {
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForList("SELECT referenced_table_name FROM information_schema.key_column_usage "
                + "WHERE table_schema=DATABASE() AND table_name='rag_citations' AND referenced_table_name IS NOT NULL",String.class))
                .containsExactly("ai_invocations");
        assertThatThrownBy(()->jdbc.update("INSERT INTO rag_record_capacity(scope,scope_id,records,metadata_bytes) VALUES('GLOBAL',0,100001,512005120)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO rag_record_capacity(scope,scope_id,records,metadata_bytes) VALUES('PROJECT',1,1,0)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void v10ThroughV12UpgradeRealV9ChatRowsWithoutChangingLegacyMeaning() throws Exception {
        String database="devmate_rag_upgrade_"+UUID.randomUUID().toString().replace("-","");
        assertThat(database).matches("devmate_rag_upgrade_[0-9a-f]{32}");
        String url=MYSQL.getJdbcUrl().replace("/"+MYSQL.getDatabaseName(),"/"+database);
        try(var admin=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var statement=admin.createStatement()) {
            statement.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");
            try {
                Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).target("9").load().migrate();
                try(var connection=DriverManager.getConnection(url,"root",MYSQL.getPassword());var sql=connection.createStatement()) {
                    sql.execute("INSERT INTO users(id,username,password) VALUES(1,'legacy','synthetic')");
                    sql.execute("INSERT INTO projects(id,owner_user_id,name) VALUES(1,1,'legacy')");
                    sql.execute("INSERT INTO conversations(id,project_id,owner_user_id,title) VALUES(1,1,1,'legacy')");
                    sql.execute("INSERT INTO conversation_messages(id,conversation_id,sequence_no,role,content) VALUES(1,1,1,'USER','original content')");
                    sql.execute("INSERT INTO ai_invocations(id,conversation_id,client_request_id,user_message_id,provider,model,prompt_template_version,status,started_at) "
                            + "VALUES(1,1,'3f8e3918-5236-43f0-9178-a3ab9d1a3312',1,'stub','synthetic','project-chat-v1','PENDING','2026-09-29 00:00:00')");
                }
                var flyway=Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
                try(var connection=DriverManager.getConnection(url,"root",MYSQL.getPassword());var sql=connection.createStatement();
                    var rows=sql.executeQuery("SELECT i.mode,i.request_sha256,i.lease_expires_at,m.content FROM ai_invocations i JOIN conversation_messages m ON m.id=i.user_message_id")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("CHAT");
                    assertThat(rows.getString(2)).isNull();assertThat(rows.getTimestamp(3)).isNull();assertThat(rows.getString(4)).isEqualTo("original content");
                }
            } finally { statement.execute("DROP DATABASE "+database); }
        }
    }
    @Test void v12PreservesV11JsonSchemaRowsAndRestrictsNewCategories() throws Exception {
        String database="devmate_rag_json_upgrade_"+UUID.randomUUID().toString().replace("-","");
        assertThat(database).matches("devmate_rag_json_upgrade_[0-9a-f]{32}");
        String url=MYSQL.getJdbcUrl().replace("/"+MYSQL.getDatabaseName(),"/"+database);
        try(var admin=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var statement=admin.createStatement()) {
            statement.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");
            try {
                Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).target("11").load().migrate();
                try(var connection=DriverManager.getConnection(url,"root",MYSQL.getPassword());var sql=connection.createStatement()) {
                    sql.execute("INSERT INTO users(id,username,password) VALUES(1,'legacy','synthetic')");
                    sql.execute("INSERT INTO projects(id,owner_user_id,name) VALUES(1,1,'legacy')");
                    sql.execute("INSERT INTO conversations(id,project_id,owner_user_id,title) VALUES(1,1,1,'legacy')");
                    sql.execute("INSERT INTO conversation_messages(id,conversation_id,sequence_no,role,content) VALUES(1,1,1,'USER','synthetic')");
                    sql.execute("INSERT INTO ai_invocations(id,conversation_id,client_request_id,user_message_id,provider,model,"
                            + "prompt_template_version,status,error_code,started_at,completed_at,mode,request_sha256,lease_expires_at) VALUES "
                            + "(1,1,'3f8e3918-5236-43f0-9178-a3ab9d1a3312',1,'stub','synthetic','project-rag-v1',"
                            + "'FAILED','AI_RESPONSE_INVALID','2026-10-10 00:00:00','2026-10-10 00:00:01','RAG','"+"0".repeat(64)
                            + "','2026-10-10 00:12:00')");
                    sql.execute("INSERT INTO rag_invocation_details(invocation_id,execution_deadline,chat_state,failure_stage,failure_category) "
                            + "VALUES(1,'2026-10-10 00:11:00','RECEIVED','RAG_OUTPUT','JSON_SCHEMA')");
                }
                var flyway=Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
                try(var connection=DriverManager.getConnection(url,"root",MYSQL.getPassword());var sql=connection.createStatement()) {
                    try(var rows=sql.executeQuery("SELECT failure_stage,failure_category FROM rag_invocation_details WHERE invocation_id=1")) {
                        assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("RAG_OUTPUT");
                        assertThat(rows.getString(2)).isEqualTo("JSON_SCHEMA");
                    }
                    sql.executeUpdate("UPDATE rag_invocation_details SET failure_category='JSON_DUPLICATE_KEY' WHERE invocation_id=1");
                    assertThatThrownBy(()->sql.executeUpdate("UPDATE rag_invocation_details SET failure_category='UNCLASSIFIED' WHERE invocation_id=1"))
                            .isInstanceOf(java.sql.SQLException.class);
                }
            } finally { statement.execute("DROP DATABASE "+database); }
        }
    }
}
