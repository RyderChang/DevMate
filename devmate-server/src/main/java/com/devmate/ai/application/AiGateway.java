package com.devmate.ai.application;

public interface AiGateway {
    boolean enabled();

    String provider();

    String model();

    AiChatResult chat(AiChatRequest request);
}
