package com.samsenpro.platform.commons;

import com.samsenpro.platform.commons.web.CorrelationId;
import com.samsenpro.platform.commons.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    @Test
    void keepsAValidIncomingCorrelationId() {
        assertThat(CorrelationId.sanitizeOrGenerate("abc-123_X.y")).isEqualTo("abc-123_X.y");
    }

    @Test
    void replacesInvalidValuesToProtectTheLogs() {
        String injected = "abc\nFAKE LOG LINE";

        String result = CorrelationId.sanitizeOrGenerate(injected);

        assertThat(result).isNotEqualTo(injected).matches("[0-9a-f-]{36}");
        assertThat(CorrelationId.sanitizeOrGenerate("x".repeat(65))).hasSize(36);
        assertThat(CorrelationId.sanitizeOrGenerate(null)).hasSize(36);
    }

    @Test
    void filterExposesTheIdInMdcDuringTheRequestAndClearsItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(CorrelationId.HEADER, "corr-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(request, response,
                new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                        seenInMdc.set(MDC.get(CorrelationId.MDC_KEY));
                    }
                });

        assertThat(seenInMdc.get()).isEqualTo("corr-42");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("corr-42");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
}
