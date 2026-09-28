package com.devmate.knowledge.infrastructure;

import com.devmate.common.api.ErrorCode;
import com.devmate.knowledge.application.*;
import com.devmate.knowledge.config.KnowledgeProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Only this infrastructure adapter knows the SDK. It never changes bucket policies. */
public final class S3ObjectStorage implements ObjectStorage {
    private final S3Client client;
    public S3ObjectStorage(KnowledgeProperties properties) {
        client = S3Client.builder().endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion())).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(properties.getConnectTimeout())
                        .socketTimeout(properties.getReadTimeout()).proxyConfiguration(ProxyConfiguration.builder()
                                .useSystemPropertyValues(false).useEnvironmentVariablesValues(false).build()))
                .overrideConfiguration(config -> config.apiCallTimeout(properties.getOperationTimeout())
                        .apiCallAttemptTimeout(properties.getOperationTimeout())
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .build();
    }
    @Override public void put(ObjectLocation location, Path file, long length, String sha256, String putToken) {
        try {
            String expected = java.util.Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256));
            var receipt = client.putObject(request -> request.bucket(location.bucket()).key(location.key()).contentLength(length).checksumSHA256(expected)
                    .contentType("text/plain; charset=utf-8").metadata(Map.of("sha256", sha256, "devmate-put-token", putToken)), RequestBody.fromFile(file));
            if (!expected.equals(receipt.checksumSHA256())) throw new StorageFailure(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE, false);
        } catch (StorageFailure error) { throw error;
        } catch (RuntimeException error) { throw safe(error, authoritativeRejection(error)); }
    }
    @Override public Verification inspect(ObjectLocation location, long length, String sha256, String token) {
        try {
            var head = client.headObject(request -> request.bucket(location.bucket()).key(location.key()));
            boolean correlated = token.equals(head.metadata().get("devmate-put-token"));
            if (head.contentLength() != length) return new Verification(true, correlated, false);
            DigestOutput output = new DigestOutput(length, OutputStream.nullOutputStream());
            var response = client.getObject(request -> request.bucket(location.bucket()).key(location.key()), ResponseTransformer.toOutputStream(output));
            correlated = token.equals(response.metadata().get("devmate-put-token"));
            return new Verification(true, correlated, response.contentLength() == length && output.count == length
                    && sha256.equals(HexFormat.of().formatHex(output.hash.digest())));
        } catch (S3Exception error) {
            if (error.statusCode() == 404 && !"NoSuchBucket".equals(error.awsErrorDetails().errorCode())) {
                // HEAD's empty 404 response can hide a missing bucket. Confirm the bucket first.
                try { client.headBucket(request -> request.bucket(location.bucket())); }
                catch (RuntimeException bucketError) { throw safe(bucketError, false); }
                return Verification.missing();
            }
            throw safe(error, false);
        } catch (RuntimeException error) { throw safe(error, false); }
    }
    @Override public void delete(ObjectLocation location) {
        try { client.deleteObject(request -> request.bucket(location.bucket()).key(location.key())); }
        catch (RuntimeException error) { throw safe(error, false); }
    }
    @Override public void read(ObjectLocation location, long maximumBytes, OutputStream destination) {
        if (maximumBytes < 1 || maximumBytes > 5L * 1024 * 1024) throw new IllegalArgumentException("Invalid internal read limit");
        try { client.getObject(request -> request.bucket(location.bucket()).key(location.key()),
                ResponseTransformer.toOutputStream(new DigestOutput(maximumBytes, destination))); }
        catch (RuntimeException error) { throw safe(error, false); }
    }
    private boolean authoritativeRejection(RuntimeException error) {
        return error instanceof S3Exception s3 && (s3.statusCode() == 400 || s3.statusCode() == 401 || s3.statusCode() == 403 || s3.statusCode() == 404);
    }
    private StorageFailure safe(RuntimeException error, boolean noWrite) {
        boolean timeout = error instanceof ApiCallTimeoutException || error instanceof ApiCallAttemptTimeoutException;
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause())
            if (cause instanceof java.net.SocketTimeoutException) timeout = true;
        return new StorageFailure(timeout ? ErrorCode.KNOWLEDGE_STORAGE_TIMEOUT : ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE, noWrite);
    }
    @Override public void close() { client.close(); }

    private static class DigestOutput extends OutputStream {
        final long maximum;
        final OutputStream destination;
        final MessageDigest hash;
        long count;
        DigestOutput(long maximum, OutputStream destination) {
            this.maximum = maximum; this.destination = destination;
            try { hash = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (count + length > maximum) throw new IOException("Internal object read limit exceeded");
            destination.write(data, offset, length); hash.update(data, offset, length); count += length;
        }
    }
}
