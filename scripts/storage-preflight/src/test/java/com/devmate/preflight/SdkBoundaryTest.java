package com.devmate.preflight;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.*;

class SdkBoundaryTest {
    private static S3Client client(HttpServer server, Duration totalTimeout) {
        return S3Client.builder().region(Region.US_EAST_1).forcePathStyle(true)
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(10))
                        .proxyConfiguration(ProxyConfiguration.builder().useSystemPropertyValues(false)
                                .useEnvironmentVariablesValues(false).build()))
                .overrideConfiguration(config -> config.apiCallTimeout(totalTimeout).apiCallAttemptTimeout(totalTimeout)
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .build();
    }

    @Test
    void serverFailureDoesNotTriggerHiddenRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var s3 = client(server, Duration.ofSeconds(30))) {
            var failure = assertThrows(S3Exception.class, () -> s3.headObject(r -> r.bucket("synthetic").key("sample")));
            assertEquals(503, failure.statusCode());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void totalDeadlineBoundsUnresponsiveServer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try (var s3 = client(server, Duration.ofSeconds(1))) {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                        assertThrows(ApiCallTimeoutException.class,
                                () -> s3.headObject(r -> r.bucket("synthetic").key("sample"))));
                assertEquals(0, entered.getCount());
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }
}
