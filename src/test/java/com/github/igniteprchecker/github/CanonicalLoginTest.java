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
 * A GitHub login was linked exactly as typed: "nsamelchev" never matched the "NSAmelchev" GitHub
 * reports as a comment's author, and a typo was linked as happily as a real login. Linking now asks
 * GitHub for the user, with the typed text in the request's path.
 */
class CanonicalLoginTest {
    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void theLoginIsSpelledTheWayGithubSpellsIt() {
        server.expect(requestTo("https://api.github.com/users/nsamelchev"))
            .andRespond(withSuccess("{\"login\":\"NSAmelchev\",\"id\":1}", MediaType.APPLICATION_JSON));

        assertThat(github.canonicalLogin("nsamelchev")).contains("NSAmelchev");
    }

    @Test
    void aTypoIsNoSuchUser() {
        server.expect(requestTo("https://api.github.com/users/nsamelchevv"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(github.canonicalLogin("nsamelchevv")).isEmpty();
        server.verify();
    }

    /** "Try again in a minute" is the honest answer then, not "no such user". */
    @Test
    void githubBeingDownIsNotNoSuchUser() {
        server.expect(requestTo("https://api.github.com/users/nsamelchev")).andRespond(withServerError());

        assertThatThrownBy(() -> github.canonicalLogin("nsamelchev")).isInstanceOf(RestClientException.class);
    }

    @Test
    void textThatCannotBeALoginIsNotSentToGithub() {
        assertThat(github.canonicalLogin("../user")).isEmpty();
        assertThat(github.canonicalLogin("nsamelchev?per_page=1")).isEmpty();
        assertThat(github.canonicalLogin("-nsamelchev")).isEmpty();

        server.verify();
    }
}
