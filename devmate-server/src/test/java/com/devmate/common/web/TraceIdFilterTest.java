package com.devmate.common.web;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TraceIdFilterTest {
    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void exposesGeneratedTraceIdAndClearsDiagnosticContext() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] observedTraceId = new String[1];

        filter.doFilter(new MockHttpServletRequest(), response, (request, chainResponse) ->
                observedTraceId[0] = MDC.get(TraceIdFilter.MDC_KEY));

        String responseTraceId = response.getHeader(TraceIdFilter.RESPONSE_HEADER);
        assertThat(responseTraceId).isEqualTo(observedTraceId[0]);
        assertThatCode(() -> UUID.fromString(responseTraceId)).doesNotThrowAnyException();
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }
}
