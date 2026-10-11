package com.devmate.conversation.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RagOutputExperimentTest {
    final Set<String> allowed = Set.of("C1", "C2", "C3", "C4", "C5");
    static String envelope(String content, String finish) throws Exception {
        return RagOutputExperimentBridge.JSON.writeValueAsString(Map.of("object", "chat.completion", "id", "fixture",
                "model", "deepseek-flash", "choices", List.of(Map.of("index", 0, "finish_reason", finish,
                "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120)));
    }
    @Test void permitsListOnlyCitationsAndRetainsUsageForShapeFailures() throws Exception {
        var success = RagOutputExperimentBridge.evaluate(envelope("{\"answer\":\"answer\",\"citationIds\":[\"C2\",\"C4\"]}", "stop"), allowed);
        assertThat(success).containsEntry("status", "SUCCEEDED").containsEntry("citationIds", List.of("C2", "C4"));
        var failed = RagOutputExperimentBridge.evaluate(envelope("{\"answer\":false,\"citationIds\":[\"C2\"]}", "stop"), allowed);
        assertThat(failed).containsEntry("failureCategory", "JSON_SHAPE").containsKey("usage").doesNotContainKeys("answer", "citationIds");
    }
    @Test void executesAllStrictNegativeCategoriesWithoutRelaxingTheValidator() throws Exception {
        var fixtures = Map.of(
                "JSON_SIZE", "x".repeat(262145),
                "JSON_DUPLICATE_KEY", "{\"answer\":\"a\",\"answer\":\"b\",\"citationIds\":[\"C1\"]}",
                "JSON_SYNTAX", "```json\n{}\n```",
                "JSON_TRAILING", "{\"answer\":\"a\",\"citationIds\":[\"C1\"]} {}",
                "JSON_SHAPE", "{\"answer\":\"a\",\"citationIds\":[\"C1\"],\"tool\":{}}",
                "ANSWER_CONTENT", "{\"answer\":\" \",\"citationIds\":[\"C1\"]}",
                "CITATION_IDS", "{\"answer\":\"a\",\"citationIds\":[\"C9\"]}",
                "CITATION_MARKERS", "{\"answer\":\"a [C2]\",\"citationIds\":[\"C1\"]}");
        for (var entry : fixtures.entrySet()) {
            var result = RagOutputExperimentBridge.evaluate(envelope(entry.getValue(), "stop"), allowed);
            assertThat(result).containsEntry("failureStage", "RAG_OUTPUT").containsEntry("failureCategory", entry.getKey())
                    .containsKey("usage").doesNotContainKeys("answer", "citationIds");
        }
    }
    @Test void emptyContentAndTruncationRemainProviderFailures() throws Exception {
        assertThat(RagOutputExperimentBridge.evaluate(envelope("", "stop"), allowed))
                .containsEntry("failureStage", "PROVIDER_RESPONSE").containsEntry("failureCategory", "MESSAGE_CONTENT");
        assertThat(RagOutputExperimentBridge.evaluate(envelope("{}", "length"), allowed))
                .containsEntry("failureCategory", "FINISH_REASON");
    }
    @Test void fourArmsVaryOnlyInstructionsAndResponseFormat() throws Exception {
        var hit = Map.ofEntries(Map.entry("pointId", "synthetic"), Map.entry("score", 0.9), Map.entry("documentId", 1),
                Map.entry("filename", "synthetic.md"), Map.entry("processingId", 1), Map.entry("indexId", 1),
                Map.entry("processingGeneration", 1), Map.entry("indexGeneration", 1), Map.entry("parserVersion", "plain-v1"),
                Map.entry("strategyVersion", "chunk-v1"), Map.entry("sourceSha256", "a".repeat(64)),
                Map.entry("chunkSha256", RagInput.sha("evidence")), Map.entry("ordinal", 0), Map.entry("start", 0),
                Map.entry("end", 8), Map.entry("startLine", 1), Map.entry("endLine", 1), Map.entry("text", "evidence"));
        String baselineUser = null;
        for (String arm : List.of("baseline", "example", "json_mode", "combined")) {
            var input = RagOutputExperimentBridge.JSON.valueToTree(Map.of("arm", arm, "question", "frozen", "hits", List.of(hit)));
            var result = RagOutputExperimentBridge.JSON.valueToTree(RagOutputExperimentBridge.request(input));
            var payload = result.path("payload");
            String user = payload.path("messages").get(1).path("content").asText();
            if (baselineUser == null) baselineUser = user;
            assertThat(user).isEqualTo(baselineUser);
            boolean example = arm.equals("example") || arm.equals("combined");
            assertThat(payload.path("messages").get(0).path("content").asText())
                    .isEqualTo(ProjectRagPromptBuilder.INSTRUCTIONS + (example ? RagOutputExperimentBridge.EXAMPLE : ""));
            assertThat(payload.has("response_format")).isEqualTo(arm.equals("json_mode") || arm.equals("combined"));
            assertThat(payload.path("max_tokens").asInt()).isEqualTo(1024);
            assertThat(payload.has("temperature") || payload.has("top_p")).isFalse();
        }
    }
}
