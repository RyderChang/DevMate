package com.devmate.conversation.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RagOutputValidator {
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Pattern INLINE = Pattern.compile("\\[C([^\\]\\r\\n]*)\\]");
    public record Answer(String text, List<String> citationIds) {}
    public Answer validate(String raw, Set<String> allowed) {
        if (raw == null || raw.length() > 262144) throw invalid(RagOutputException.Issue.JSON_SCHEMA);
        try (var parser = json.createParser(raw)) {
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || root.size() != 2 || parser.nextToken() != null
                    || !root.has("answer") || !root.get("answer").isTextual()
                    || !root.has("citationIds") || !root.get("citationIds").isArray()) throw invalid(RagOutputException.Issue.JSON_SCHEMA);
            String answer = root.get("answer").textValue();
            if (answer.isBlank() || !RagInput.unicode(answer) || answer.codePointCount(0, answer.length()) > 8000
                    || answer.getBytes(StandardCharsets.UTF_8).length > 32768) throw invalid(RagOutputException.Issue.ANSWER_CONTENT);
            var ids = new ArrayList<String>();
            for (var id : root.get("citationIds")) {
                if (!id.isTextual() || !allowed.contains(id.textValue()) || ids.contains(id.textValue())) throw invalid(RagOutputException.Issue.CITATION_IDS);
                ids.add(id.textValue());
            }
            if (ids.isEmpty() || ids.size() > 5 || new HashSet<>(ids).size() != ids.size()) throw invalid(RagOutputException.Issue.CITATION_IDS);
            var markers = INLINE.matcher(answer);
            while (markers.find()) if (!ids.contains("C" + markers.group(1))) throw invalid(RagOutputException.Issue.CITATION_MARKERS);
            return new Answer(answer, List.copyOf(ids));
        } catch (java.io.IOException error) { throw invalid(RagOutputException.Issue.JSON_SCHEMA); }
    }
    private RagOutputException invalid(RagOutputException.Issue issue) { return new RagOutputException(issue); }
}
