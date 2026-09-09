package com.devmate.common.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsSuccessResponseWithData() throws Exception {
        Result<String> result = Result.success("value");
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("success");
        assertThat(result.getData()).isEqualTo("value");
        assertThat(json.get("code").asInt()).isEqualTo(200);
        assertThat(json.get("message").asText()).isEqualTo("success");
        assertThat(json.get("data").asText()).isEqualTo("value");
    }

    @Test
    void createsSuccessResponseWithoutData() {
        Result<Void> result = Result.success();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("success");
        assertThat(result.getData()).isNull();
    }

    @Test
    void createsFailureResponseFromErrorCode() {
        Result<Void> result = Result.error(ErrorCode.INVALID_PARAMETER);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("Invalid request parameter");
        assertThat(result.getData()).isNull();
    }
}
