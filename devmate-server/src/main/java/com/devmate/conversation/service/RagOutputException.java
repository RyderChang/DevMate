package com.devmate.conversation.service;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;

/** Only a fixed validation category crosses into the durable RAG diagnostic. */
final class RagOutputException extends BusinessException {
    enum Issue { JSON_ABSENT, JSON_SIZE, JSON_DUPLICATE_KEY, JSON_SYNTAX, JSON_TRAILING,
        JSON_SHAPE, ANSWER_CONTENT, CITATION_IDS, CITATION_MARKERS }
    private final Issue issue;
    RagOutputException(Issue issue) {
        super(ErrorCode.AI_RESPONSE_INVALID);
        this.issue = issue;
    }
    Issue issue() { return issue; }
}
