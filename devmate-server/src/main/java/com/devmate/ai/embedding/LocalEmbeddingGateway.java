package com.devmate.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LocalEmbeddingGateway implements EmbeddingGateway, AutoCloseable {
    private final LocalJsonClient http;
    private final Duration inferenceDeadline;
    private volatile boolean ready;
    public LocalEmbeddingGateway(String origin) { this(origin, Duration.ofSeconds(300),true); }
    /** Disabled writes retain a status-only recovery path without requiring startup network access. */
    public LocalEmbeddingGateway(String origin,boolean verifyAtStartup) { this(origin,Duration.ofSeconds(300),verifyAtStartup); }
    public LocalEmbeddingGateway(String origin, Duration inferenceDeadline) {
        this(origin,inferenceDeadline,true);
    }
    private LocalEmbeddingGateway(String origin,Duration inferenceDeadline,boolean verifyAtStartup) {
        http = new LocalJsonClient(origin); this.inferenceDeadline = inferenceDeadline;
        if(verifyAtStartup)try { ensureReady(); } catch (RuntimeException failure) { close(); throw failure; }
    }
    private synchronized void ensureReady(){
        if(ready)return;
        check(http.call("GET", "/spec", null, Duration.ofSeconds(10)));
        if(!tokenize(List.of("hello", "e\u0301", "<|endoftext|>")).equals(List.of(2,2,2)))throw new EmbeddingFailure("SPEC_MISMATCH",true);
        ready=true;
    }
    private void check(JsonNode node) {
        if (!EmbeddingSpec.FINGERPRINT.equals(node.path("fingerprint").asText()) || !EmbeddingSpec.ID.equals(node.path("spec").asText()))
            throw new EmbeddingFailure("SPEC_MISMATCH", true);
    }
    @Override public List<Integer> count(List<String> texts) {
        ensureReady();return tokenize(texts);
    }
    private List<Integer> tokenize(List<String> texts){
        EmbeddingSpec.inputs(texts);
        JsonNode response = http.call("POST", "/tokenize", Map.of("spec", EmbeddingSpec.ID, "input", texts), Duration.ofSeconds(10));
        check(response); var result = new ArrayList<Integer>();
        if (!response.path("counts").isArray() || response.path("counts").size() != texts.size()) throw new EmbeddingFailure("INVALID_RESPONSE", true);
        for (JsonNode count : response.path("counts")) { int value = LocalJsonClient.integer(count); if (value < 1 || value > 6000) throw new EmbeddingFailure("TOKEN_LIMIT", true); result.add(value); }
        return List.copyOf(result);
    }
    @Override public List<float[]> embed(String operation, List<String> texts, List<Integer> counts) {
        ensureReady();
        UUID.fromString(operation); EmbeddingSpec.inputs(texts); EmbeddingSpec.counts(counts, texts.size());
        JsonNode response = http.call("POST", "/embeddings", Map.of("spec", EmbeddingSpec.ID, "operation_id", operation, "input", texts), inferenceDeadline,operation);
        check(response);
        if (!EmbeddingSpec.MODEL.equals(response.path("model").asText()) || !operation.equals(response.path("operation_id").asText())
                || !"list".equals(response.path("object").asText()) || !response.path("data").isArray() || response.path("data").size() != texts.size())
            throw new EmbeddingFailure("INVALID_RESPONSE", true);
        int expected = counts.stream().mapToInt(Integer::intValue).sum();
        if (LocalJsonClient.integer(response.path("usage").path("total_tokens")) != expected
                || response.path("usage").has("prompt_tokens") && LocalJsonClient.integer(response.path("usage").path("prompt_tokens")) != expected)
            throw new EmbeddingFailure("INVALID_USAGE", true);
        float[][] values = new float[texts.size()][];
        for (JsonNode row : response.path("data")) {
            int index = LocalJsonClient.integer(row.path("index"));
            if (index >= values.length || values[index] != null || !"embedding".equals(row.path("object").asText()) || !row.path("embedding").isArray() || row.path("embedding").size() != 1024)
                throw new EmbeddingFailure("INVALID_RESPONSE", true);
            float[] vector = new float[1024]; int i = 0;
            for (JsonNode value : row.path("embedding")) {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || Math.abs(value.doubleValue()) > Float.MAX_VALUE) throw new EmbeddingFailure("INVALID_RESPONSE", true);
                vector[i++] = value.floatValue();
            }
            values[index] = vector;
        }
        List<float[]> result = List.of(values); EmbeddingSpec.vectors(result, texts.size()); return result;
    }
    @Override public boolean ended(String operation) {
        try { var response = http.call("GET", "/operations/" + UUID.fromString(operation), null, Duration.ofSeconds(10)); check(response);
            return operation.equals(response.path("operation_id").asText()) && java.util.Set.of("SUCCEEDED", "TERMINATED").contains(response.path("state").asText());
        } catch (RuntimeException failure) { return false; }
    }
    @Override public void close() { http.close(); }
}
