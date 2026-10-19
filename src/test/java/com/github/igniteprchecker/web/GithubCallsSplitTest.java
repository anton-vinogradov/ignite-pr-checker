package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The status page counted a call by its name alone: a PR comment of the app account went under users' own tokens,
 * and a fix commit under a user's token went under the app's. The server now names users' calls apart.
 */
class GithubCallsSplitTest {
    @Test
    void callsUnderUsersOwnTokensAreToldFromTheAppsByTheirName() throws Exception {
        JsonNode split = PageFunctions.run("status.js", """
            const call = (total, fail) => ({ total, fail, avgLatencyMs: 100 });
            out(githubCalls({ prComment: call(3, 1), react: call(2, 0), 'pat:prComment': call(5, 0),
                'pat:commit': call(4, 2) }));
            """, "githubCalls");

        assertThat(split.get("app").propertyNames()).containsExactlyInAnyOrder("prComment", "react");
        assertThat(split.get("pat").propertyNames()).containsExactlyInAnyOrder("prComment", "commit");
        assertThat(split.get("appCalls").asInt()).isEqualTo(5);
        assertThat(split.get("appFails").asInt()).isEqualTo(1);
    }
}
