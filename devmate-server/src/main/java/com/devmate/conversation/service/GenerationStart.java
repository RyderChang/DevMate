package com.devmate.conversation.service;

import com.devmate.conversation.vo.SendMessageResponse;

record GenerationStart(GenerationContext context, SendMessageResponse existingResponse) {
    static GenerationStart created(GenerationContext context) {
        return new GenerationStart(context, null);
    }

    static GenerationStart existing(SendMessageResponse response) {
        return new GenerationStart(null, response);
    }

    boolean isExisting() {
        return existingResponse != null;
    }
}
