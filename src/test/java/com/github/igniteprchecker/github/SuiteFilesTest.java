package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Adding an author's test class to a suite reads the author's open PRs, one search, and the files of the class's
 * module at the PR's head, two calls: the parent directory names the module's tree, and the tree lists its files.
 */
class SuiteFilesTest {
    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void theAuthorsOpenPrsComeFromOneSearch() {
        server.expect(requestTo("https://api.github.com/search/issues?q=repo:apache/ignite%20is:pr%20is:open"
            + "%20author:alice-v&per_page=100"))
            .andRespond(withSuccess("""
                {"total_count":2,"incomplete_results":false,"items":[
                  {"number":13800,"title":"IGNITE-29000 Check function parameters","user":{"login":"alice-v"}},
                  {"number":13775,"title":"IGNITE-28990 Something else","user":{"login":"alice-v"}}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(github.openPrsBy("alice-v")).containsExactly(13800, 13775);
        assertThat(github.openPrsBy("not a login")).as("asks nothing for what no login is").isEmpty();
        server.verify();
    }

    @Test
    void theModulesFilesComeFromItsTreeAtTheHead() {
        server.expect(requestTo("https://api.github.com/repos/alice/ignite/contents/modules/calcite/src/test"
            + "?ref=abc1234"))
            .andRespond(withSuccess("""
                [{"name":"java","path":"modules/calcite/src/test/java","type":"dir","sha":"tree-sha"},
                 {"name":"resources","path":"modules/calcite/src/test/resources","type":"dir","sha":"other"}]
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/alice/ignite/git/trees/tree-sha?recursive=1"))
            .andRespond(withSuccess("""
                {"sha":"tree-sha","truncated":false,"tree":[
                  {"path":"org/apache/ignite/testsuites","type":"tree","sha":"t1"},
                  {"path":"org/apache/ignite/testsuites/UtilTestSuite.java","type":"blob","sha":"b1","size":1406}]}
                """, MediaType.APPLICATION_JSON));

        GithubClient.Tree tree = github.filesUnder("alice/ignite", "abc1234", "modules/calcite/src/test/java");

        assertThat(tree.truncated()).isFalse();
        assertThat(tree.files()).containsExactly(new GithubClient.Tree.File(
            "modules/calcite/src/test/java/org/apache/ignite/testsuites/UtilTestSuite.java", 1406));
        server.verify();
    }
}
