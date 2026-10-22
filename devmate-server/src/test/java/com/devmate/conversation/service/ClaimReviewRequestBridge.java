package com.devmate.conversation.service;

import com.devmate.ai.config.AiProperties;
import com.devmate.knowledge.vo.RetrievalHit;
import com.devmate.project.vo.ProjectResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline request preparation using the unchanged production v1 builder; captured pipes only. */
public final class ClaimReviewRequestBridge {
    private ClaimReviewRequestBridge() {}
    static final String EXAMPLE = """
            JSON output example (shape only; do not copy the example answer or its citation):
            {"answer":"An evidence-based answer.","citationIds":["C1"]}
            The top-level object must have exactly these two keys. answer is a JSON string;
            citationIds is a JSON array of unique strings from the supplied documents.
            Do not nest the object, rename keys, add source objects, or return an array.
            """;

    public static void main(String[] args) {
        var json = new ObjectMapper().findAndRegisterModules()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        try {
            byte[] bytes = System.in.readNBytes(1048577);
            if (bytes.length > 1048576) throw new IllegalArgumentException();
            var input = json.readTree(bytes);
            String arm = input.path("arm").asText();
            if (!Set.of("baseline", "example", "json_mode", "combined").contains(arm))
                throw new IllegalArgumentException();
            var hits = json.readerForListOf(RetrievalHit.class).<List<RetrievalHit>>readValue(input.get("hits"));
            var provided = new ProjectRagPromptBuilder(json, new AiProperties()).build(
                    new ProjectResponse(1L, "DEV-027 public docs", "Isolated real answer evaluation",
                            Instant.EPOCH, Instant.EPOCH), List.of(), input.path("question").asText(), hits);
            if (hits.size() != 5 || provided.sources().size() != 5) throw new IllegalArgumentException();
            boolean example = arm.equals("example") || arm.equals("combined");
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("model", "deepseek-flash");
            payload.put("stream", false);
            payload.put("thinking", Map.of("type", "disabled"));
            payload.put("max_tokens", provided.request().maxOutputTokens());
            String instructions = provided.request().instructions() + (example ? EXAMPLE : "");
            payload.put("messages", List.of(Map.of("role", "system", "content", instructions),
                    Map.of("role", "user", "content", provided.request().messages().getFirst().content())));
            if (arm.equals("json_mode") || arm.equals("combined"))
                payload.put("response_format", Map.of("type", "json_object"));
            String serialized = json.writeValueAsString(payload);
            if (serialized.codePointCount(0, serialized.length()) > 24000
                    || serialized.getBytes(StandardCharsets.UTF_8).length > 98304) throw new IllegalArgumentException();
            System.out.write(json.writeValueAsBytes(Map.of("payload", payload,
                    "templateVersion", ProjectRagPromptBuilder.TEMPLATE_VERSION,
                    "exampleVersion", example ? "project-rag-json-example-exp-v1" : "NONE")));
        } catch (Throwable error) {
            // Never echo any request, exception text or traceback.
            System.exit(1);
        }
    }
}
