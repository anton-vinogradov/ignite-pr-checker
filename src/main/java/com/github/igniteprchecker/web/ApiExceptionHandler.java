package com.github.igniteprchecker.web;

import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns outbound failures into answers the page can act on, instead of a raw 500 with a full stack trace in
 * the log and the UI: a call to TeamCity, JIRA or GitHub that got no answer (a DNS blip, a reset connection, a
 * read timeout) becomes a 502 naming that service, a TeamCity error status a 502 with a human message, and a
 * token TeamCity no longer accepts a 401 that sends the user to the login form.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    static final String TOKEN_REJECTED = "TeamCity rejected your token (revoked or expired) — log in again";

    static final String FORBIDDEN = "ci2 refused the request (403). Its firewall sometimes blocks valid requests, "
        + "so try again in a minute; if Rerun or Cancel keeps failing, your ci2 account may lack the rights for it";

    static final String PREVIOUS_RUNALL_CANCELLED =
        "Your previous RunAll was cancelled, but queuing the new one failed";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final TcClient tc;

    public ApiExceptionHandler(TcClient tc) {
        this.tc = tc;
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> noAnswer(ResourceAccessException e) {
        String service = e instanceof OutboundHttp.NoAnswer named ? named.service() : "A service this page needs";
        log.warn("{} did not answer: {}", service, e.getMessage());

        return ResponseEntity.status(502).body(Map.of("error", service + " did not answer — try again in a moment"));
    }

    /**
     * A 401 means the user's own token only when TeamCity refuses it outright: a request can join an
     * analysis started under someone else's pooled token. A 403 is ci2's firewall as often as missing
     * rights, so it never logs anyone out.
     */
    @ExceptionHandler(TcResponseException.class)
    public ResponseEntity<Map<String, Object>> teamCityRefused(TcResponseException e, HttpServletRequest req) {
        int status = e.getStatusCode().value();
        if (status == 401 && req.getAttribute(AuthInterceptor.TOKEN_ATTR) instanceof String token
            && tc.tokenRejected(token)) {
            log.info("TeamCity rejected the token of {}", req.getAttribute(AuthInterceptor.USER_ATTR));

            return ResponseEntity.status(401).body(Map.of("error", TOKEN_REJECTED, "tokenRejected", true));
        }

        log.warn("TeamCity answered {} to {} {}", status, req.getMethod(), req.getRequestURI());

        return ResponseEntity.status(502).body(Map.of("error", status == 403 ? FORBIDDEN
            : "TeamCity answered " + status + " — try again in a moment"));
    }

    /** Why queuing failed, as it would be answered alone, led by the news that the previous chain is gone. */
    @ExceptionHandler(ReplacementNotQueuedException.class)
    public ResponseEntity<Map<String, Object>> replacementNotQueued(ReplacementNotQueuedException e,
        HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> alone;
        if (e.getCause() instanceof TcResponseException refused)
            alone = teamCityRefused(refused, req);
        else if (e.getCause() instanceof ResourceAccessException unreachable)
            alone = noAnswer(unreachable);
        else {
            log.warn("RunAll not queued after cancelling the previous one", e.getCause());

            return ResponseEntity.status(502).body(Map.of("error", PREVIOUS_RUNALL_CANCELLED + " — try RunAll again",
                "replaced", e.replaced()));
        }

        Map<String, Object> body = new HashMap<>(alone.getBody());
        body.put("error", PREVIOUS_RUNALL_CANCELLED + ": " + body.get("error"));
        body.put("replaced", e.replaced());

        return ResponseEntity.status(alone.getStatusCode()).body(body);
    }
}
