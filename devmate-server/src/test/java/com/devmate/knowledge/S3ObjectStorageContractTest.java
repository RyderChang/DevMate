package com.devmate.knowledge;

import com.devmate.knowledge.application.*;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.S3ObjectStorage;
import com.github.dockerjava.api.model.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.*;

/** Contract tests exercise the production adapter, not just the standalone prerequisite probe. */
class S3ObjectStorageContractTest {
    static GenericContainer<?> minio;
    static KnowledgeProperties properties;
    static S3Client administrator;
    static S3ObjectStorage storage;
    @TempDir Path directory;

    @BeforeAll static void start() throws Exception {
        Properties lock = new Properties();
        try (var input = Files.newInputStream(Path.of("../scripts/storage-preflight/environment.properties"))) { lock.load(input); }
        String image = lock.getProperty("minio.image");
        String identity = DockerClientFactory.instance().client().inspectImageCmd(image).exec().getId();
        assertThat(identity).isIn(lock.getProperty("minio.manifestDigest"), lock.getProperty("minio.configDigest"));
        String user = UUID.randomUUID().toString(); String password = UUID.randomUUID().toString();
        minio = new GenericContainer<>(DockerImageName.parse(image)).withImagePullPolicy(ignored -> false)
                .withEnv("MINIO_ROOT_USER", user).withEnv("MINIO_ROOT_PASSWORD", password).withEnv("MINIO_BROWSER", "off")
                .withLabel("devmate.knowledge-test", "dev016").withExposedPorts(9000)
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(9000))))
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofSeconds(90)));
        minio.start();
        properties = settings("http://" + minio.getHost() + ":" + minio.getMappedPort(9000), user, password);
        administrator = S3Client.builder().endpointOverride(URI.create(properties.getEndpoint())).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user, password))).build();
        administrator.createBucket(request -> request.bucket(properties.getBucket()));
        storage = new S3ObjectStorage(properties);
    }
    @AfterAll static void stop() {
        if (storage != null) storage.close(); if (administrator != null) administrator.close(); if (minio != null) minio.close();
    }

    @Test void privateBucketStoresOriginalBytesWithValidatedSha256ReceiptAndBoundedRead() throws Exception {
        for (int size : new int[]{32, 5 * 1024 * 1024}) {
            byte[] input = new byte[size]; Arrays.fill(input, (byte) 'x');
            String sha = sha(input); String token = UUID.randomUUID().toString();
            var location = location(); Path file = directory.resolve(UUID.randomUUID().toString()); Files.write(file, input);
            storage.put(location, file, input.length, sha, token);
            assertThat(storage.inspect(location, input.length, sha, token)).isEqualTo(new Verification(true, true, true));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); storage.read(location, size, bytes);
            assertThat(bytes.toByteArray()).containsExactly(input);
            try (var anonymous = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build()) {
                var response = anonymous.send(HttpRequest.newBuilder(URI.create(properties.getEndpoint() + "/" + location.bucket() + "/" + location.key()))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(403);
            }
            ByteArrayOutputStream limited = new ByteArrayOutputStream();
            assertThatThrownBy(() -> storage.read(location, size - 1, limited)).isInstanceOf(StorageFailure.class);
            assertThatThrownBy(() -> storage.read(location, size - 1, limited)).isInstanceOfSatisfying(StorageFailure.class,
                    error -> assertThat(error.retryableRead()).isFalse());
            assertThat(limited.size()).isLessThanOrEqualTo(size - 1);
            storage.delete(location); storage.delete(location);
            assertThat(storage.inspect(location, size, sha, token)).isEqualTo(Verification.missing());
        }
    }

    @Test void productionReadFeedsStrictStreamingParserWithOriginalIntegrityAndNormalizedUnicodePositions() throws Exception {
        String text = "\ufeff" + "😀中\r\n\r\n[link](https://example.invalid)\n".repeat(150);
        byte[] input=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var location=location(); Path file=directory.resolve(UUID.randomUUID().toString()); Files.write(file,input);
        storage.put(location,file,input.length,sha(input),UUID.randomUUID().toString());
        var parser=new TextChunker(input.length,sha(input),System.nanoTime()+30_000_000_000L,System::nanoTime);
        storage.read(location,TextChunker.MAX_SOURCE_BYTES,parser); var result=parser.finish();
        String normalized=text.substring(1).replace("\r\n","\n").replace('\r','\n');
        assertThat(result.normalizedSha256()).isEqualTo(sha(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        int[] points=normalized.codePoints().toArray();
        for (TextChunk chunk : result.chunks()) assertThat(chunk.text()).isEqualTo(new String(points,chunk.start(),chunk.end()-chunk.start()));
        var wrong=new TextChunker(input.length,"a".repeat(64),System.nanoTime()+30_000_000_000L,System::nanoTime);
        storage.read(location,TextChunker.MAX_SOURCE_BYTES,wrong);
        assertThatThrownBy(wrong::finish).isInstanceOfSatisfying(ProcessingFailure.class,error -> assertThat(error.code()).isEqualTo("INTEGRITY_MISMATCH"));
        storage.delete(location);
    }

    @Test void readDeadlineIsBoundedAndTimedOutReadHasOneAttempt() throws Exception {
        var requests=new AtomicInteger(); var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange -> {
            requests.incrementAndGet();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try (var timedOut=new S3ObjectStorage(shortSettings(server))) {
            assertThatThrownBy(() -> timedOut.read(location(),32,new ByteArrayOutputStream())).isInstanceOfSatisfying(StorageFailure.class,error -> {
                assertThat(error.errorCode()).isEqualTo(com.devmate.common.api.ErrorCode.KNOWLEDGE_STORAGE_TIMEOUT);
                assertThat(error.retryableRead()).isTrue(); assertThat(error.getCause()).isNull();
            });
            assertThat(requests.get()).isOne();
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void integrityInspectionUsesBytesRatherThanEtagOrClientMetadata() throws Exception {
        var location = location(); String token = UUID.randomUUID().toString();
        byte[] expected = "correct".getBytes(); byte[] actual = "corrupt".getBytes(); String sha = sha(expected);
        administrator.putObject(request -> request.bucket(location.bucket()).key(location.key()).metadata(Map.of("sha256", sha, "devmate-put-token", token)), RequestBody.fromBytes(actual));
        assertThat(storage.inspect(location, expected.length, sha, token)).isEqualTo(new Verification(true, true, false));
        assertThat(storage.inspect(location, expected.length, sha, UUID.randomUUID().toString()).correlatedWrite()).isFalse();
        storage.delete(location);
    }

    @Test void rejectedCredentialsAndMissingBucketAreSafeFailuresRatherThanObjectAbsence() {
        var wrong = settings(properties.getEndpoint(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        try (var denied = new S3ObjectStorage(wrong)) {
            assertThatThrownBy(() -> denied.inspect(location(), 1, "x", "x"))
                    .isInstanceOfSatisfying(StorageFailure.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(com.devmate.common.api.ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);
                        assertThat(error.getCause()).isNull(); assertThat(error.getMessage()).doesNotContain(wrong.getAccessKey(), wrong.getSecretKey(), properties.getBucket());
                    });
        }
        assertThatThrownBy(() -> storage.inspect(new ObjectLocation("missing-" + UUID.randomUUID(), "synthetic"), 1, "x", "x"))
                .isInstanceOf(StorageFailure.class);
    }

    @Test void unavailableServiceHasOnePutAttemptAndNoSdkExceptionCause() throws Exception {
        var requests = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            byte[] error = "<Error><Code>ServiceUnavailable</Code></Error>".getBytes();
            exchange.sendResponseHeaders(503, error.length); exchange.getResponseBody().write(error); exchange.close();
        });
        server.start();
        try (var failing = new S3ObjectStorage(shortSettings(server))) {
            Path file = directory.resolve("synthetic"); Files.writeString(file, "x");
            assertThatThrownBy(() -> failing.put(location(), file, 1, sha("x".getBytes()), UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(StorageFailure.class, error -> { assertThat(error.definitelyNoWrite()).isFalse(); assertThat(error.getCause()).isNull(); });
            assertThat(requests.get()).isOne();
        } finally { server.stop(0); }
    }

    @Test void uploadTotalTimeoutIsBoundedAndDoesNotClaimThatTheRemoteWriteEnded() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            received.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try (var timedOut = new S3ObjectStorage(shortSettings(server))) {
            Path file = directory.resolve("synthetic"); Files.writeString(file, "x");
            assertThatThrownBy(() -> timedOut.put(location(), file, 1, sha("x".getBytes()), UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(StorageFailure.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(com.devmate.common.api.ErrorCode.KNOWLEDGE_STORAGE_TIMEOUT);
                        assertThat(error.definitelyNoWrite()).isFalse();
                    });
            assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); server.stop(0); }
    }

    private ObjectLocation location() { return new ObjectLocation(properties.getBucket(), "synthetic-" + UUID.randomUUID()); }
    private String sha(byte[] input) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
    private static KnowledgeProperties settings(String endpoint, String user, String password) {
        var settings = new KnowledgeProperties(); settings.setEnabled(true); settings.setAllowLocalHttp(true); settings.setEndpoint(endpoint);
        settings.setAccessKey(user); settings.setSecretKey(password); settings.setBucket("synthetic-" + UUID.randomUUID()); settings.afterPropertiesSet(); return settings;
    }
    private KnowledgeProperties shortSettings(HttpServer server) {
        var settings = settings("http://127.0.0.1:" + server.getAddress().getPort(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        settings.setOperationTimeout(Duration.ofMillis(500)); settings.setConnectTimeout(Duration.ofMillis(100)); settings.setReadTimeout(Duration.ofMillis(200)); settings.afterPropertiesSet(); return settings;
    }
}
