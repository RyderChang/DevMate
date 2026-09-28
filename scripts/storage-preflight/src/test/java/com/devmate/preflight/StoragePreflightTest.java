package com.devmate.preflight;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.*;

/** Dependency/environment probe only; no DevMate document or recovery implementation. */
class StoragePreflightTest {
    private static Properties lock() throws Exception {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(Path.of("environment.properties"))) {
            properties.load(input);
        }
        return properties;
    }

    private static String randomCredential() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static S3Client client(URI endpoint, String user, String password) {
        return S3Client.builder()
                .endpointOverride(endpoint).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user, password)))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(10))
                        .proxyConfiguration(ProxyConfiguration.builder()
                                .useSystemPropertyValues(false).useEnvironmentVariablesValues(false).build()))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .build();
    }

    @Test
    void pinnedMinioSupportsPrivateSynchronousObjectOperations() throws Exception {
        Properties config = lock();
        String image = config.getProperty("minio.image");
        String localId = DockerClientFactory.instance().client().inspectImageCmd(image).exec().getId();
        assertTrue(localId.equals(config.getProperty("minio.manifestDigest"))
                        || localId.equals(config.getProperty("minio.configDigest")),
                "Build the locked local MinIO image before running this probe");
        String user = randomCredential();
        String password = randomCredential();
        try (var minio = new GenericContainer<>(DockerImageName.parse(image))
                .withImagePullPolicy(ignored -> false)
                .withLabel("devmate.preflight", "dev016")
                .withEnv("MINIO_ROOT_USER", user).withEnv("MINIO_ROOT_PASSWORD", password)
                .withEnv("MINIO_BROWSER", "off")
                .withExposedPorts(9000)
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                                new ExposedPort(9000))))
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)
                        .withStartupTimeout(Duration.ofSeconds(90)))) {
            minio.start();
            URI endpoint = URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
            String bucket = "dev016-" + UUID.randomUUID();
            try (S3Client s3 = client(endpoint, user, password)) {
                // Environment preparation creates the bucket; the future application adapter must not.
                s3.createBucket(request -> request.bucket(bucket));
                for (int size : new int[]{32, 5 * 1024 * 1024}) {
                    byte[] content = new byte[size];
                    Arrays.fill(content, (byte) 'x');
                    String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
                    String key = "synthetic-" + size;
                    try (var input = new ByteArrayInputStream(content)) {
                        s3.putObject(request -> request.bucket(bucket).key(key).contentLength((long) size)
                                        .contentType("text/plain").metadata(Map.of("sha256", sha256)),
                                RequestBody.fromInputStream(input, size));
                    }
                    var head = s3.headObject(request -> request.bucket(bucket).key(key));
                    assertEquals(size, head.contentLength());
                    assertEquals(sha256, head.metadata().get("sha256"));
                    byte[] retrieved = s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
                    assertArrayEquals(content, retrieved);
                    assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(retrieved)));
                    try (var anonymous = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                        var response = anonymous.send(HttpRequest.newBuilder(endpoint.resolve("/" + bucket + "/" + key))
                                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
                        assertEquals(403, response.statusCode());
                    }
                    try (var wrongIdentity = client(endpoint, randomCredential(), randomCredential())) {
                        S3Exception denied = assertThrows(S3Exception.class,
                                () -> wrongIdentity.headObject(request -> request.bucket(bucket).key(key)));
                        assertEquals(403, denied.statusCode());
                    }
                    s3.deleteObject(request -> request.bucket(bucket).key(key));
                    s3.deleteObject(request -> request.bucket(bucket).key(key));
                    S3Exception missing = assertThrows(S3Exception.class,
                            () -> s3.headObject(request -> request.bucket(bucket).key(key)));
                    assertEquals(404, missing.statusCode());
                }
                s3.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @Test
    void pinnedMysqlStartsWithJava21AndExistingTestcontainers() throws Exception {
        Properties config = lock();
        DockerImageName image = DockerImageName.parse(config.getProperty("mysql.image"))
                .asCompatibleSubstituteFor("mysql");
        try (var mysql = new MySQLContainer<>(image).withDatabaseName("preflight")
                .withUsername("preflight").withPassword(randomCredential())
                .withLabel("devmate.preflight", "dev016")
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                                new ExposedPort(3306))))
                .withCommand("--default-time-zone=+00:00")) {
            mysql.start();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT VERSION(), @@global.time_zone, 1")) {
                assertTrue(result.next());
                assertEquals("8.4.6", result.getString(1));
                assertEquals("+00:00", result.getString(2));
                assertEquals(1, result.getInt(3));
            }
        }
    }
}
