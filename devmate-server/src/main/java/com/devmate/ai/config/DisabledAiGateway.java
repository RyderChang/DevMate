package com.devmate.ai.config;

import com.devmate.ai.application.AiChatRequest;
import com.devmate.ai.application.AiChatResult;
import com.devmate.ai.application.AiGateway;
import com.devmate.ai.application.AiGatewayException;
import com.devmate.common.api.ErrorCode;

final class DisabledAiGateway implements AiGateway {
    private final AiProperties properties;

    DisabledAiGateway(AiProperties properties) {
        this.properties = properties;
    }

    @Override public boolean enabled() { return false; }
    @Override public String provider() { return properties.getProvider(); }
    @Override public String model() { return properties.getOpenai().getModel(); }

    @Override
    public AiChatResult chat(AiChatRequest request) {
        throw new AiGatewayException(ErrorCode.AI_SERVICE_DISABLED);
    }
}
