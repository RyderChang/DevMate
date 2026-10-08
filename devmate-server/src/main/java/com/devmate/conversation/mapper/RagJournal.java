package com.devmate.conversation.mapper;

import com.devmate.ai.application.AiChatResult;
import com.devmate.conversation.vo.CitationSource;
import com.devmate.conversation.vo.RagSummary;
import com.devmate.knowledge.vo.RetrievalResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Conversation-owned records only. No source bodies, prompts, model JSON or vectors are persisted here. */
@Repository
public class RagJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public RagJournal(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public boolean reserve(long project, long conversation) {
        String[] scopes = {"GLOBAL", "PROJECT", "CONVERSATION"};
        long[] ids = {0, project, conversation};
        int[] limits = {100000, 10000, 1000};
        for (int i = 0; i < scopes.length; i++) {
            jdbc.update("INSERT INTO rag_record_capacity(scope,scope_id) VALUES(?,?) ON DUPLICATE KEY UPDATE scope_id=scope_id", scopes[i], ids[i]);
            if (jdbc.update("UPDATE rag_record_capacity SET records=records+1,metadata_bytes=metadata_bytes+5120 "
                    + "WHERE scope=? AND scope_id=? AND records<?", scopes[i], ids[i], limits[i]) != 1) return false;
        }
        return true;
    }
    public void begin(long invocation, LocalDateTime deadline) {
        jdbc.update("INSERT INTO rag_invocation_details(invocation_id,execution_deadline) VALUES(?,?)", invocation, deadline);
    }
    public LocalDateTime deadline(long invocation) {
        return jdbc.queryForObject("SELECT execution_deadline FROM rag_invocation_details WHERE invocation_id=?", LocalDateTime.class, invocation);
    }
    public void dispatched(long invocation, RetrievalResponse result, LocalDateTime checked) {
        if (jdbc.update("UPDATE rag_invocation_details SET retrieval_id=?,spec=?,query_tokens=?,rounds=?,inspected_points=?,"
                + "checked_at=?,chat_state='DISPATCHED' WHERE invocation_id=? AND chat_state='NOT_SENT'",
                result.retrievalId(), result.spec(), result.queryTokens(), result.rounds(), result.inspectedPoints(), checked, invocation) != 1)
            throw new IllegalStateException("RAG dispatch cannot be confirmed");
    }
    public void receipt(long invocation, AiChatResult result) {
        if (jdbc.update("UPDATE rag_invocation_details SET chat_state='RECEIVED' WHERE invocation_id=? AND chat_state IN ('DISPATCHED','UNKNOWN')", invocation) != 1)
            throw new IllegalStateException("RAG receipt cannot be confirmed");
        usage(invocation, result);
    }
    public void usage(long invocation, AiChatResult result) {
        jdbc.update("UPDATE ai_invocations SET provider_request_id=?,input_tokens=?,output_tokens=?,total_tokens=?,duration_ms=? WHERE id=?",
                result.providerRequestId(), result.inputTokens(), result.outputTokens(), result.totalTokens(), result.durationMs(), invocation);
    }
    public void unknown(long invocation) {
        jdbc.update("UPDATE rag_invocation_details SET chat_state='UNKNOWN' WHERE invocation_id=? AND chat_state='DISPATCHED'", invocation);
    }
    public void checked(long invocation, LocalDateTime checked) {
        jdbc.update("UPDATE rag_invocation_details SET checked_at=? WHERE invocation_id=?", checked, invocation);
    }
    public record SavedCitation(String id, CitationSource source) {}
    public void save(long invocation, String id, CitationSource source) {
        try { jdbc.update("INSERT INTO rag_citations(invocation_id,citation_id,source_snapshot) VALUES(?,?,?)", invocation, id, json.writeValueAsString(source)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Citation serialization failed"); }
    }
    public List<SavedCitation> citations(long invocation) {
        return jdbc.query("SELECT citation_id,source_snapshot FROM rag_citations WHERE invocation_id=? ORDER BY citation_id", (r, n) -> {
            try { return new SavedCitation(r.getString(1), json.readValue(r.getString(2), CitationSource.class)); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Citation snapshot cannot be read"); }
        }, invocation);
    }
    public RagSummary summary(long invocation) {
        return jdbc.queryForObject("SELECT r.*,i.prompt_template_version FROM rag_invocation_details r "
                + "JOIN ai_invocations i ON i.id=r.invocation_id WHERE r.invocation_id=?", (r, n) ->
                new RagSummary(r.getString("retrieval_id"), r.getString("spec"), r.getInt("query_tokens"),
                        r.getInt("rounds"), r.getInt("inspected_points"), r.getString("prompt_template_version"),
                        r.getTimestamp("checked_at").toInstant(), "NORMALIZED_UNICODE_CODE_POINT"), invocation);
    }
    public record HistoryEvidence(RagSummary rag, List<SavedCitation> citations) {}
    private record HistoryRow(long messageId, RagSummary rag, SavedCitation citation) {}

    /** One bounded page read; only successful RAG answers can expose saved provenance. */
    public Map<Long, HistoryEvidence> historyEvidence(long owner, long project, long conversation,
                                                      List<Long> assistantMessageIds) {
        if (assistantMessageIds.isEmpty()) return Map.of();
        if (assistantMessageIds.size() > 100) throw new IllegalArgumentException("Message page bound exceeded");
        var args = new ArrayList<Object>(List.of(owner, project, conversation));
        args.addAll(assistantMessageIds);
        String sql = "SELECT i.assistant_message_id,i.prompt_template_version,r.retrieval_id,r.spec,"
                + "r.query_tokens,r.rounds,r.inspected_points,r.checked_at,k.citation_id,k.source_snapshot "
                + "FROM ai_invocations i JOIN conversations c ON c.id=i.conversation_id "
                + "JOIN projects p ON p.id=c.project_id AND p.owner_user_id=c.owner_user_id "
                + "JOIN rag_invocation_details r ON r.invocation_id=i.id "
                + "JOIN rag_citations k ON k.invocation_id=i.id "
                + "WHERE c.owner_user_id=? AND c.project_id=? AND c.id=? AND p.deleted=0 "
                + "AND i.mode='RAG' AND i.status='SUCCEEDED' AND i.assistant_message_id IN ("
                + String.join(",", Collections.nCopies(assistantMessageIds.size(), "?")) + ") "
                + "ORDER BY i.assistant_message_id,k.citation_id";
        List<HistoryRow> rows = jdbc.query(sql, (r, n) -> {
            try {
                return new HistoryRow(r.getLong("assistant_message_id"),
                        new RagSummary(r.getString("retrieval_id"), r.getString("spec"), r.getInt("query_tokens"),
                                r.getInt("rounds"), r.getInt("inspected_points"),
                                r.getString("prompt_template_version"), r.getTimestamp("checked_at").toInstant(),
                                "NORMALIZED_UNICODE_CODE_POINT"),
                        new SavedCitation(r.getString("citation_id"),
                                json.readValue(r.getString("source_snapshot"), CitationSource.class)));
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Citation snapshot cannot be read");
            }
        }, args.toArray());
        if (rows.size() > 500) throw new IllegalStateException("Citation page bound exceeded");
        Map<Long, List<SavedCitation>> citations = new HashMap<>();
        Map<Long, RagSummary> summaries = new HashMap<>();
        for (HistoryRow row : rows) {
            summaries.put(row.messageId(), row.rag());
            var messageCitations = citations.computeIfAbsent(row.messageId(), ignored -> new ArrayList<>());
            messageCitations.add(row.citation());
            if (messageCitations.size() > 5) throw new IllegalStateException("Citation bound exceeded");
        }
        Map<Long, HistoryEvidence> result = new HashMap<>();
        citations.forEach((id, saved) -> result.put(id, new HistoryEvidence(summaries.get(id), List.copyOf(saved))));
        return Map.copyOf(result);
    }
    public List<Long> expired(LocalDateTime now) {
        return jdbc.queryForList("SELECT i.id FROM ai_invocations i JOIN rag_invocation_details r ON r.invocation_id=i.id "
                + "WHERE i.status='PENDING' AND r.execution_deadline<=? ORDER BY r.execution_deadline,i.id LIMIT 20", Long.class, now);
    }
    public record Owner(long owner, long project, long conversation) {}
    public Owner owner(long invocation) {
        return jdbc.queryForObject("SELECT c.owner_user_id,c.project_id,c.id FROM ai_invocations i "
                + "JOIN conversations c ON c.id=i.conversation_id WHERE i.id=?", (r, n) -> new Owner(r.getLong(1), r.getLong(2), r.getLong(3)), invocation);
    }
}
