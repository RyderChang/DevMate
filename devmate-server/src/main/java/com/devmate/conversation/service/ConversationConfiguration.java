package com.devmate.conversation.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(RagProperties.class)
public class ConversationConfiguration {
    @Bean
    Clock conversationClock() {
        return Clock.systemUTC();
    }
}
