package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The checker kept any GitHub token GitHub put a name to, for the PR comment as well: a committer's classic token with
 * public_repo pushes to apache/ignite, and one with admin:org or delete_repo was taken all the same. It keeps a token
 * only to commit to the user's PR branch now, and only one that does no more than that needs.
 */
class CommitTokensTest {
    @Test
    void aFineGrainedTokenIsKeptWithNothingToSay() {
        assertThat(CommitTokens.refusal("github_pat_11ABC", null)).isEmpty();
        assertThat(CommitTokens.note("github_pat_11ABC", null)).isNull();
    }

    @Test
    void aClassicTokenWithPublicRepoIsKeptAndSaidToReachEveryPublicRepo() {
        assertThat(CommitTokens.refusal("ghp_x", List.of("public_repo"))).isEmpty();
        assertThat(CommitTokens.note("ghp_x", List.of("public_repo"))).isEqualTo("Your saved token is a classic one:"
            + " it can push to every public repository you can push to, apache/ignite too if you are a committer. A"
            + " fine-grained token for your fork can push there only.");
    }

    @Test
    void aClassicTokenWithRepoIsKeptWithAWarning() {
        assertThat(CommitTokens.refusal("ghp_x", List.of("repo", "read:org"))).isEmpty();
        assertThat(CommitTokens.note("ghp_x", List.of("repo", "read:org")))
            .contains("and with the repo scope to your private ones as well; public_repo is enough");
    }

    @Test
    void aClassicTokenThatAdministersDeletesOrChangesWorkflowsIsRefused() {
        assertThat(CommitTokens.refusal("ghp_x", List.of("public_repo", "admin:org", "delete_repo", "workflow")))
            .hasValue("this classic token has admin:org, delete_repo, workflow: with it, anyone who reads it could"
                + " administer, delete repositories or change workflows, and the checker only commits. Create one"
                + " without them: a fine-grained token for your fork, or a classic one with public_repo");
        assertThat(CommitTokens.refusal("ghp_x", List.of("repo", "admin:repo_hook"))).isPresent();
    }

    @Test
    void aClassicTokenThatCannotCommitIsRefused() {
        assertThat(CommitTokens.refusal("ghp_x", List.of())).hasValueSatisfying(r -> assertThat(r)
            .startsWith("this classic token has no public_repo scope, so it cannot commit to your PR's branch"));
        assertThat(CommitTokens.refusal("ghp_x", List.of("read:user", "gist"))).isPresent();
    }

    /** GitHub names a classic token's scopes in a header of its answer, and a fine-grained token's in none. */
    @Test
    void theScopesAreReadOffGithubsAnswer() {
        RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        ObjectMapper mapper = Jackson2Defaults.mapper();
        GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300), mapper,
            new Metrics(mapper), http.build());
        server.expect(requestTo("https://api.github.com/user")).andExpect(header("Authorization", "Bearer ghp_x"))
            .andRespond(withSuccess("{\"login\": \"alice-v\", \"id\": 42}", MediaType.APPLICATION_JSON)
                .header("X-OAuth-Scopes", "public_repo, admin:org"));
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\": \"alice-v\", \"id\": 42}", MediaType.APPLICATION_JSON));

        assertThat(github.tokenOwner("ghp_x")).hasValueSatisfying(o -> {
            assertThat(o.user()).isEqualTo(new GithubClient.GhUser("alice-v", 42L));
            assertThat(o.scopes()).containsExactly("public_repo", "admin:org");
        });
        assertThat(github.tokenOwner("github_pat_x")).hasValueSatisfying(o -> assertThat(o.scopes()).isNull());
        server.verify();
    }
}
