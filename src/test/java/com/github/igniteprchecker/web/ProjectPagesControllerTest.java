package com.github.igniteprchecker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectPagesControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProjectPagesController()).build();

    /** Each supported project has a direct page address. Spring forwards it to the shared page. */
    @ParameterizedTest
    @ValueSource(strings = {"ignite", "python", "php", "nodejs", "extensions"})
    void servesProjectPage(String project) throws Exception {
        mvc.perform(get("/" + project).param("pr", "42"))
            .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    /** The home address opens Ignite without interpreting old project and PR query parameters. */
    @Test
    void homeDoesNotKeepLegacyQuery() throws Exception {
        mvc.perform(get("/").param("repo", "php").param("pr", "42"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/ignite"));
    }

    /** An unknown project cannot resolve to another project's PR page. */
    @Test
    void refusesUnknownProject() throws Exception {
        mvc.perform(get("/unknown").param("pr", "42")).andExpect(status().isNotFound());
    }
}
