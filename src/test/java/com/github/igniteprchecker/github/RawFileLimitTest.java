package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The checkstyle autofix read up to 50 files of a PR whole into memory and only then looked at their size, in a
 * service with a 512 MB heap that exits on running out of it. A "#" or "?" in a file name cut the request short:
 * only spaces were encoded in the path.
 */
class RawFileLimitTest {
    private static final String FILE = "https://api.github.com/repos/author/ignite/contents/modules/core/A.java?ref=f195e0c";

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void aFileWithinTheLimitIsRead() {
        server.expect(requestTo(FILE)).andRespond(withSuccess("class A {}\n", MediaType.TEXT_PLAIN));

        assertThat(github.rawFileUpTo("author/ignite", "f195e0c", "modules/core/A.java", 400_000))
            .contains("class A {}\n");
    }

    @Test
    void aBiggerFileIsNotKept() {
        byte[] big = new byte[400_001];
        Arrays.fill(big, (byte)'x');
        server.expect(requestTo(FILE)).andRespond(withSuccess(big, MediaType.TEXT_PLAIN));

        assertThat(github.rawFileUpTo("author/ignite", "f195e0c", "modules/core/A.java", 400_000)).isEmpty();
    }

    /** GitHub announced no length: the body is read up to the limit, and no further. */
    @Test
    void aBiggerFileIsReadNoFurtherThanTheLimit() {
        long[] served = new long[1];
        InputStream eightMegabytes = new InputStream() {
            @Override
            public int read() {
                if (served[0] == 8L << 20)
                    return -1;

                served[0]++;

                return 'x';
            }
        };
        server.expect(requestTo(FILE)).andRespond(request -> new MockClientHttpResponse(eightMegabytes, HttpStatus.OK));

        assertThat(github.rawFileUpTo("author/ignite", "f195e0c", "modules/core/A.java", 400_000)).isEmpty();
        assertThat(served[0]).isEqualTo(400_001);
    }

    @Test
    void aFileGithubSaysIsBiggerIsNotRead() {
        server.expect(requestTo(FILE)).andRespond(withSuccess("class A {}\n", MediaType.TEXT_PLAIN)
            .header("Content-Length", "52428800"));

        assertThat(github.rawFileUpTo("author/ignite", "f195e0c", "modules/core/A.java", 400_000)).isEmpty();
    }

    @Test
    void anErrorIsNotTakenForTheFile() {
        server.expect(requestTo(FILE)).andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"message\":\"Not Found\"}"));

        assertThatThrownBy(() -> github.rawFileUpTo("author/ignite", "f195e0c", "modules/core/A.java", 400_000))
            .isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void everyPartOfThePathIsEncoded() {
        server.expect(requestTo("https://api.github.com/repos/author/ignite/contents/modules/my%20core/A%23B%3FC.java"
            + "?ref=f195e0c")).andRespond(withSuccess("class A {}\n", MediaType.TEXT_PLAIN));

        assertThat(github.rawFile("author/ignite", "f195e0c", "modules/my core/A#B?C.java")).isEqualTo("class A {}\n");
        server.verify();
    }
}
