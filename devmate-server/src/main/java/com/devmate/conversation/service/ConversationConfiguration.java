package com.devmate.conversation.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConversationConfiguration {
    @Bean
    Clock conversationClock() {
        return Clock.systemUTC();
    }
}
