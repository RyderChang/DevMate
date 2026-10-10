package com.devmate.conversation.service;

import com.devmate.common.api.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagOutputDiagnosticTest {
    private final RagOutputValidator validator = new RagOutputValidator();
    private static final Set<String> ALLOWED = Set.of("C1");
    private static final String VALID = "{\"answer\":\"supported [C1]\",\"citationIds\":[\"C1\"]}";

    @Test void separatesJsonFailuresWithoutExposingOutputOrParserMessages() {
        assertIssue(null, "JSON_ABSENT");
        assertIssue("x".repeat(262145), "JSON_SIZE");
        assertIssue("{\"answer\":\"first\",\"answer\":\"second\",\"citationIds\":[\"C1\"]}", "JSON_DUPLICATE_KEY");
        assertIssue("```json\n" + VALID + "\n```", "JSON_SYNTAX");
        assertIssue(VALID.substring(0, VALID.length() - 1), "JSON_SYNTAX");
        assertIssue(VALID + " {}", "JSON_TRAILING");
        assertIssue(VALID + " private trailing text", "JSON_TRAILING");
        assertIssue("[]", "JSON_SHAPE");
        assertIssue("{\"answer\":true,\"citationIds\":[\"C1\"]}", "JSON_SHAPE");
        assertIssue("{\"answer\":\"x\",\"citationIds\":[\"C1\"],\"tool\":{}}", "JSON_SHAPE");
        assertIssue("{}", "JSON_SHAPE");
        assertThat(validator.validate(VALID, ALLOWED).text()).isEqualTo("supported [C1]");
    }

    @Test void keepsExistingContentAndCitationCategories() {
        assertIssue("{\"answer\":\" \",\"citationIds\":[\"C1\"]}", "ANSWER_CONTENT");
        assertIssue("{\"answer\":\"x\",\"citationIds\":[\"C9\"]}", "CITATION_IDS");
        assertIssue("{\"answer\":\"[C9]\",\"citationIds\":[\"C1\"]}", "CITATION_MARKERS");
    }

    private void assertIssue(String raw, String expected) {
        assertThatThrownBy(() -> validator.validate(raw, ALLOWED))
                .isInstanceOfSatisfying(RagOutputException.class, error -> {
                    assertThat(error.issue().name()).isEqualTo(expected);
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID.getMessage());
                    assertThat(error.getCause()).isNull();
                });
    }
}
