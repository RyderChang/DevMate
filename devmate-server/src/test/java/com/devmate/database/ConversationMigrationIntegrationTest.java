package com.devmate.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationMigrationIntegrationTest extends MySqlIntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ai_invocations");
        jdbc.update("DELETE FROM conversation_messages");
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void createsConversationTablesConstraintsAndStableIndexes() {
        assertThat(tableColumns("conversations")).containsExactlyInAnyOrder(
                "id", "project_id", "owner_user_id", "title", "generation_state",
                "generation_started_at", "create_time", "update_time");
        assertThat(tableColumns("conversation_messages")).contains("conversation_id", "sequence_no", "role", "content");
        assertThat(tableColumns("ai_invocations")).contains("client_request_id", "user_message_id",
                "assistant_message_id", "provider", "model", "prompt_template_version", "status",
                "input_tokens", "output_tokens", "total_tokens", "duration_ms");
        assertThat(indexColumns("conversations", "idx_conversations_owner_project_updated_id"))
                .containsExactly("owner_user_id", "project_id", "update_time", "id");
        assertThat(indexColumns("conversation_messages", "uk_conversation_messages_sequence"))
                .containsExactly("conversation_id", "sequence_no");
        assertThat(indexColumns("ai_invocations", "uk_ai_invocations_request"))
                .containsExactly("conversation_id", "client_request_id");
        flyway.validate();
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history "
                + "WHERE success=1", Integer.class)).isEqualTo(5);
    }

    @Test
    void enforcesOwnerRelationshipMessageSequenceAndRequestIdempotency() {
        jdbc.update("INSERT INTO users(username,password) VALUES ('owner-one','hash'),('owner-two','hash')");
        Long ownerOne = id("owner-one");
        Long ownerTwo = id("owner-two");
        jdbc.update("INSERT INTO projects(owner_user_id,name) VALUES (?, 'Project')", ownerOne);
        Long projectId = jdbc.queryForObject("SELECT id FROM projects WHERE owner_user_id=?", Long.class, ownerOne);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO conversations(project_id,owner_user_id,title) "
                + "VALUES (?,?,'Mismatch')", projectId, ownerTwo)).isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO conversations(project_id,owner_user_id,title) VALUES (?,?,'Conversation')",
                projectId, ownerOne);
        Long conversationId = jdbc.queryForObject("SELECT id FROM conversations", Long.class);
        jdbc.update("INSERT INTO conversation_messages(conversation_id,sequence_no,role,content) "
                + "VALUES (?,1,'USER','hello')", conversationId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO conversation_messages(conversation_id,sequence_no,role,content) "
                + "VALUES (?,1,'ASSISTANT','duplicate')", conversationId)).isInstanceOf(DataAccessException.class);
        Long userMessageId = jdbc.queryForObject("SELECT id FROM conversation_messages", Long.class);
        jdbc.update("INSERT INTO conversation_messages(conversation_id,sequence_no,role,content) "
                + "VALUES (?,2,'USER','second')", conversationId);
        Long secondMessageId = jdbc.queryForObject("SELECT id FROM conversation_messages "
                + "WHERE conversation_id=? AND sequence_no=2", Long.class, conversationId);
        String requestId = "5abf96bb-58d8-4f18-97f6-70d877cf6257";
        jdbc.update("INSERT INTO ai_invocations(conversation_id,client_request_id,user_message_id,provider,model,"
                        + "prompt_template_version,status,started_at) VALUES (?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP(6))",
                conversationId, requestId, userMessageId, "openai", "test-model", "project-chat-v1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_invocations(conversation_id,client_request_id,"
                        + "user_message_id,provider,model,prompt_template_version,status,started_at) "
                        + "VALUES (?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP(6))",
                conversationId, requestId, secondMessageId, "openai", "test-model", "project-chat-v1"))
                .isInstanceOf(DataAccessException.class);
    }

    private java.util.List<String> tableColumns(String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position", String.class, table);
    }

    private java.util.List<String> indexColumns(String table, String index) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index",
                String.class, table, index);
    }

    private Long id(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    }
}
