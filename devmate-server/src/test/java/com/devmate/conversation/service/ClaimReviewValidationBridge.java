package com.devmate.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;

/** Offline test adapter: bodies travel only over captured in-memory pipes, never argv/files. */
public final class ClaimReviewValidationBridge {
    private ClaimReviewValidationBridge() {}

    public static void main(String[] args) {
        var json = new ObjectMapper();
        try {
            byte[] bytes = System.in.readNBytes(1048577);
            if (bytes.length > 1048576) throw new IllegalArgumentException();
            var input = json.readTree(bytes);
            var allowed = new HashSet<String>();
            for (var id : input.get("allowed")) allowed.add(id.textValue());
            var answer = new RagOutputValidator().validate(input.get("raw").textValue(), allowed);
            System.out.write(json.writeValueAsBytes(Map.of("status", "SUCCEEDED",
                    "answer", answer.text(), "citationIds", answer.citationIds())));
        } catch (RagOutputException error) {
            writeFailure(json, error.issue().name());
        } catch (Throwable error) {
            writeFailure(json, "VALIDATOR_UNAVAILABLE");
        }
    }

    private static void writeFailure(ObjectMapper json, String code) {
        try {
            System.out.write(json.writeValueAsBytes(Map.of("status", "FAILED", "code", code)));
        } catch (Throwable error) {
            // No exception text/traceback: caller treats missing pipe output as unknown.
            System.exit(1);
        }
    }
}
