package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The checker also answers at v888764.hosted-by-vdsina.com, and to a browser every other host at hosted-by-vdsina.com
 * is the same site: SameSite=Lax sent the visitor's session cookie with a POST from their pages. An empty POST to
 * /api/auto-visa-all?visa=true switched on the visitor's auto-visa, which stored their TeamCity and JIRA tokens.
 */
class SameOriginFilterTest {
    private static final String HOST = "ignite-pr-checker.is-a.dev";

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe()).addFilters(new SameOriginFilter())
        .build();

    @RestController
    static class Probe {
        @PostMapping("/api/probe")
        String change() {
            return "changed";
        }

        @GetMapping("/api/probe")
        String read() {
            return "read";
        }
    }

    @Test
    void aPostFromAPageOfAnotherSiteIsRefused() throws Exception {
        for (String site : new String[] {"cross-site", "same-site"}) {
            MockHttpServletResponse res = send(post("/api/probe").header("Sec-Fetch-Site", site));

            assertThat(res.getStatus()).as(site).isEqualTo(403);
            assertThat(res.getContentAsString()).isEqualTo(SameOriginFilter.REFUSED);
        }
    }

    @Test
    void aPostFromTheCheckersOwnPageGoesThrough() throws Exception {
        assertThat(send(post("/api/probe").header("Sec-Fetch-Site", "same-origin")).getContentAsString())
            .isEqualTo("changed");
        assertThat(send(post("/api/probe").header("Sec-Fetch-Site", "none")).getStatus()).isEqualTo(200);
    }

    /**
     * A browser without Sec-Fetch-Site sends Origin, compared with where the request went, as APP_PUBLIC_URL may be
     * empty.
     */
    @Test
    void withoutSecFetchSiteTheOriginDecides() throws Exception {
        assertThat(send(proxied(post("/api/probe")).header("Origin", "https://v888770.hosted-by-vdsina.com"))
            .getStatus()).isEqualTo(403);
        assertThat(send(proxied(post("/api/probe")).header("Origin", "https://" + HOST)).getStatus())
            .isEqualTo(200);
        assertThat(send(post("/api/probe").header("Host", "localhost:8080").header("Origin", "http://localhost:8080"))
            .getStatus()).as("a local run, no proxy").isEqualTo(200);
    }

    @Test
    void aScriptAndAReadGoThrough() throws Exception {
        assertThat(send(post("/api/probe")).getStatus()).as("no Origin, no Sec-Fetch-Site").isEqualTo(200);
        assertThat(send(get("/api/probe").header("Sec-Fetch-Site", "cross-site")).getContentAsString())
            .isEqualTo("read");
    }

    /** As Caddy passes a request on: the Host the browser sent, the scheme it used. */
    private static MockHttpServletRequestBuilder proxied(MockHttpServletRequestBuilder req) {
        return req.header("Host", HOST).header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", HOST);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse();
    }
}
