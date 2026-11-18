package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Anyone can post a body to /api/login, and one of 20 MB was read whole into a heap of 512 MB before the token in it
 * was looked at. No endpoint reads more than a token or two.
 */
class RequestBodyLimitFilterTest {
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

    @Test
    void aBodyThatNamesItsSizeIsRefusedUnread() throws Exception {
        MockHttpServletRequest req = login("x".repeat(RequestBodyLimitFilter.MAX_BYTES + 1));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).isEqualTo("{\"error\":\"Request body too large\"}");
        assertThat(chain.getRequest()).as("no endpoint is reached").isNull();
    }

    /** A body sent in chunks names no size: it is read, and the reading stops at the limit. */
    @Test
    void aBodyWithoutASizeIsReadUpToTheLimit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        req.setContent("x".repeat(RequestBodyLimitFilter.MAX_BYTES + 1).getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThatThrownBy(() -> chain.getRequest().getInputStream().readAllBytes()).isInstanceOf(IOException.class);
    }

    @Test
    void aTokenGoesThrough() throws Exception {
        MockHttpServletRequest req = login("{\"token\":\"" + "t".repeat(512) + "\"}");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(req);
    }

    private static MockHttpServletRequest login(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));

        return req;
    }
}
