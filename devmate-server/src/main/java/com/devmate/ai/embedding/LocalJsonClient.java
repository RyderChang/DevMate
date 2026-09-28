package com.devmate.ai.embedding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.List;

/** Total deadline includes headers and bounded body reading. Redirects and non-loopback origins are rejected. */
public final class LocalJsonClient implements AutoCloseable {
    private final URI origin;
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public LocalJsonClient(String address) {
        origin = URI.create(address);
        if (!"http".equals(origin.getScheme()) || !java.util.Set.of("127.0.0.1", "[::1]").contains(origin.getHost())
                || origin.getPort() < 1 || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) throw new IllegalArgumentException("Local origin must be a literal loopback HTTP origin");
    }
    public JsonNode call(String method, String path, Object body, Duration deadline) {
        CompletableFuture<HttpResponse<byte[]>> task = null;
        try {
                var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(deadline).header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
                task = client.sendAsync(request, info -> new LimitedBody());
                var response = task.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new EmbeddingFailure("REMOTE_REJECTED", false);
                    byte[] bytes = response.body();
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    try (var parser = json.createParser(text)) {
                        JsonNode value = json.readTree(parser);
                        if (value == null || !value.isObject() || parser.nextToken() != null) throw new EmbeddingFailure("INVALID_RESPONSE", false);
                        return value;
                    }
        } catch (EmbeddingFailure failure) { throw failure; }
        catch (Exception failure) { throw new EmbeddingFailure("REMOTE_UNCONFIRMED", false); }
        finally { if(task != null && !task.isDone()) task.cancel(true); }
    }
    public static int integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) throw new EmbeddingFailure("INVALID_RESPONSE", true);
        return node.intValue();
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>1048576-bytes.size()){subscription.cancel();result.completeExceptionally(new EmbeddingFailure("INVALID_RESPONSE",false));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    @Override public void close() { client.shutdownNow(); }
}
