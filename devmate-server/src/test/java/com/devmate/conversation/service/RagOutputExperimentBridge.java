package com.devmate.conversation.service;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.ai.application.AiMessage;
import com.devmate.ai.config.AiProperties;
import com.devmate.ai.infrastructure.deepseek.DeepSeekChatGateway;
import com.devmate.knowledge.vo.RetrievalHit;
import com.devmate.project.vo.ProjectResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Offline pipe bridge for the experiment. Its stdout contains sensitive transient data;
 * consume it in memory, never redirect it to logs or files. Not included in the application JAR. */
public final class RagOutputExperimentBridge {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final String EXAMPLE_VERSION = "project-rag-json-example-exp-v1";
    static final String EXAMPLE = """
            JSON output example (shape only; do not copy the example answer or its citation):
            {"answer":"An evidence-based answer.","citationIds":["C1"]}
            The top-level object must have exactly these two keys. answer is a JSON string;
            citationIds is a JSON array of unique strings from the supplied documents.
            Do not nest the object, rename keys, add source objects, or return an array.
            """;

    static Map<String, Object> request(JsonNode input) throws Exception {
        String arm = input.path("arm").asText();
        if (!Set.of("baseline", "example", "json_mode", "combined").contains(arm))
            throw new IllegalArgumentException();
        var hits = JSON.readerForListOf(RetrievalHit.class).<List<RetrievalHit>>readValue(input.get("hits"));
        var provided = new ProjectRagPromptBuilder(JSON, new AiProperties()).build(
                new ProjectResponse(1L, "DEV-027 public docs", "Isolated real answer evaluation", Instant.EPOCH, Instant.EPOCH),
                List.of(), input.path("question").asText(), hits);
        boolean example = arm.equals("example") || arm.equals("combined");
        boolean mode = arm.equals("json_mode") || arm.equals("combined");
        String instructions = provided.request().instructions() + (example ? EXAMPLE : "");
        var body = new LinkedHashMap<String, Object>();
        body.put("model", "deepseek-flash");
        body.put("stream", false);
        body.put("thinking", Map.of("type", "disabled"));
        body.put("max_tokens", provided.request().maxOutputTokens());
        body.put("messages", List.of(Map.of("role", "system", "content", instructions),
                Map.of("role", "user", "content", provided.request().messages().getFirst().content())));
        if (mode) body.put("response_format", Map.of("type", "json_object"));
        String serialized = JSON.writeValueAsString(body);
        if (provided.sources().size() != hits.size() || serialized.codePointCount(0, serialized.length()) > 24000
                || serialized.getBytes(StandardCharsets.UTF_8).length > 98304) throw new IllegalArgumentException();
        return Map.of("templateVersion", example ? EXAMPLE_VERSION : ProjectRagPromptBuilder.TEMPLATE_VERSION,
                "payload", body, "sourceIds", provided.sources().keySet());
    }

    static Map<String, Object> evaluate(String envelope, Set<String> allowed) {
        var result = new LinkedHashMap<String, Object>();
        var builder = RestClient.builder().baseUrl("http://experiment.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://experiment.invalid/chat/completions"))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
        try {
            // Real provider parsing and usage checks, with no external network.
            var receipt = new DeepSeekChatGateway(builder.build(), JSON, new AiProperties()).chat(
                    new AiChatRequest("json", List.of(new AiMessage(AiMessage.Role.USER, "fixture")), 1024));
            result.put("usage", Map.of("inputTokens", receipt.inputTokens(), "outputTokens", receipt.outputTokens(),
                    "totalTokens", receipt.totalTokens()));
            try {
                var answer = new RagOutputValidator().validate(receipt.content(), allowed);
                result.put("status", "SUCCEEDED");
                result.put("answer", answer.text()); // Only over the private memory pipe; collector removes it.
                result.put("citationIds", answer.citationIds());
            } catch (RagOutputException error) {
                result.put("status", "FAILED");
                result.put("failureStage", "RAG_OUTPUT");
                result.put("failureCategory", error.issue().name());
            }
        } catch (AiGatewayException error) {
            result.put("status", "FAILED");
            var diagnostic = RagFailureDiagnostic.gateway(error.getResponseIssue());
            result.put("failureStage", diagnostic.stage());
            result.put("failureCategory", diagnostic.category());
        }
        server.verify();
        return result;
    }

    public static void main(String[] args) {
        try {
            // One bounded request per process; no diagnostics may echo input or exception text.
            byte[] bytes = System.in.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException();
            JsonNode input = JSON.readTree(bytes);
            Object output = switch (input.path("action").asText()) {
                case "request" -> request(input);
                case "evaluate" -> evaluate(input.path("envelope").asText(),
                        JSON.readerForListOf(String.class).<List<String>>readValue(input.get("allowed")).stream()
                                .collect(java.util.stream.Collectors.toSet()));
                default -> throw new IllegalArgumentException();
            };
            System.out.write(JSON.writeValueAsBytes(output));
        } catch (Exception error) {
            System.out.print("{\"bridgeError\":\"INVALID_EXPERIMENT_INPUT\"}");
            System.exit(1);
        }
    }
}
