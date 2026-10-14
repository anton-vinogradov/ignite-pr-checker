package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A login a GitHub token proved before the checker kept account ids gets the id of the account it names: GitHub is
 * asked for the user, with the login in the request's path. "nsamelchev" is "NSAmelchev" to GitHub, and a login given
 * up names nobody.
 */
class UserByLoginTest {
    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void theLoginIsSpelledTheWayGithubSpellsIt() {
        server.expect(requestTo("https://api.github.com/users/nsamelchev"))
            .andRespond(withSuccess("{\"login\":\"NSAmelchev\",\"id\":1}", MediaType.APPLICATION_JSON));

        assertThat(github.userByLogin("nsamelchev")).contains(new GithubClient.GhUser("NSAmelchev", 1));
    }

    @Test
    void aTypoIsNoSuchUser() {
        server.expect(requestTo("https://api.github.com/users/nsamelchevv"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(github.userByLogin("nsamelchevv")).isEmpty();
        server.verify();
    }

    /** "Try again in a minute" is the honest answer then, not "no such user". */
    @Test
    void githubBeingDownIsNotNoSuchUser() {
        server.expect(requestTo("https://api.github.com/users/nsamelchev")).andRespond(withServerError());

        assertThatThrownBy(() -> github.userByLogin("nsamelchev")).isInstanceOf(RestClientException.class);
    }

    @Test
    void textThatCannotBeALoginIsNotSentToGithub() {
        assertThat(github.userByLogin("../user")).isEmpty();
        assertThat(github.userByLogin("nsamelchev?per_page=1")).isEmpty();
        assertThat(github.userByLogin("-nsamelchev")).isEmpty();

        server.verify();
    }
}
