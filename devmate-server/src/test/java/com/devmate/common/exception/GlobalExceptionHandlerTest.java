package com.devmate.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTest.ExceptionTestController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unexpectedExceptionReturnsSafeUnifiedResponse() throws Exception {
        mockMvc.perform(get("/test/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(not(containsString("sensitive failure detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void businessExceptionUsesExplicitStatusAndSafeMessage() throws Exception {
        mockMvc.perform(get("/test/business-error"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Request conflicts with current state"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void invalidRequestBodyUsesUnifiedParameterResponse() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message")
                        .value("Invalid request parameter: name: must not be blank"));
    }

    @Test
    void constraintViolationUsesUnifiedParameterResponse() throws Exception {
        mockMvc.perform(get("/test/query").param("count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("must be greater than or equal to 1")));
    }

    @RestController
    @Validated
    static class ExceptionTestController {

        @GetMapping("/test/unexpected-error")
        String fail() {
            throw new IllegalStateException("sensitive failure detail");
        }

        @GetMapping("/test/business-error")
        String businessError() {
            throw new BusinessException(400, "Request conflicts with current state", HttpStatus.CONFLICT);
        }

        @PostMapping("/test/body")
        String body(@Valid @RequestBody TestRequest request) {
            return request.name();
        }

        @GetMapping("/test/query")
        int query(@RequestParam @Min(1) int count) {
            return count;
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
