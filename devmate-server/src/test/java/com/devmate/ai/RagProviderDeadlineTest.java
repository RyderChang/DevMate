package com.devmate.ai;

import com.devmate.ai.application.*;
import com.devmate.ai.config.AiConfiguration;
import com.devmate.common.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class RagProviderDeadlineTest {
    @Test void configuredChatHttpBodyUsesRemainingDeadlineAndSendsOnlyOnePost() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/responses",exchange->{
            try {
                requests.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write('{');exchange.getResponseBody().flush();entered.countDown();release.await(5,TimeUnit.SECONDS);
            } catch(Exception ignored) { /* Closing the cancelled synthetic request is expected. */ }
            finally { exchange.close(); }
        });server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(AiConfiguration.class).withBean(ObjectMapper.class,ObjectMapper::new)
                    .withPropertyValues("devmate.ai.enabled=true", "devmate.ai.openai.api-key="+UUID.randomUUID(),
                            "devmate.ai.openai.model=synthetic", "devmate.ai.openai.base-url=http://127.0.0.1:"+server.getAddress().getPort())
                    .run(context->{
                        assertThat(context).hasNotFailed();var gateway=context.getBean(AiGateway.class);
                        try(var budget=CallBudget.open(Duration.ofMillis(750))) {
                            assertThatThrownBy(()->gateway.chat(new AiChatRequest("synthetic instructions",
                                    List.of(new AiMessage(AiMessage.Role.USER,"synthetic query")),10)))
                                    .isInstanceOfSatisfying(AiGatewayException.class,failure->assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_TIMEOUT));
                        }
                        assertThat(entered.getCount()).isZero();assertThat(requests).hasValue(1);
                    });
        } finally { release.countDown();server.stop(0); }
    }
}
