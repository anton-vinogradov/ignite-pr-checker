package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;

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
        + "so try again in a minute; if Run, Rerun or Cancel my runs keeps failing, your ci2 account may lack the "
        + "rights for it";

    static final String QUEUE_FORBIDDEN = "ci2 refused to queue it (403): your ci2 account may not run builds here. "
        + "If it can, try again in a minute: ci2's firewall sometimes blocks valid requests";

    static final String PREVIOUS_RUNALL_CANCELLED =
        "Your previous RunAll was cancelled, but queuing the new one failed";

    static final String BAD_JSON = "bad JSON";

    static final String BAD_PARAMETERS = "bad parameters";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final TcClient tc;

    private final Warmer warmer;

    public ApiExceptionHandler(TcClient tc, Warmer warmer) {
        this.tc = tc;
        this.warmer = warmer;
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> noAnswer(ResourceAccessException e) {
        String service = e instanceof OutboundHttp.NoAnswer named ? named.service() : "A service this page needs";
        log.warn("{} did not answer: {}", service, e.getMessage());

        return ResponseEntity.status(502).body(Map.of("error", service + " did not answer — try again in a moment"));
    }

    /**
     * A 401 means the user's own token only when TeamCity refuses it outright: a request can join an
     * analysis started under someone else's pooled token. Then the token's sessions are refused from now on. A 403 is
     * ci2's firewall as often as missing rights, so it never logs anyone out.
     */
    @ExceptionHandler(TcResponseException.class)
    public ResponseEntity<Map<String, Object>> teamCityRefused(TcResponseException e, HttpServletRequest req) {
        int status = e.getStatusCode().value();
        if (status == 401 && req.getAttribute(AuthInterceptor.TOKEN_ATTR) instanceof String token
            && LiveTokenGuard.refusedNow(tc, warmer, token)) {
            log.info("TeamCity rejected the token of {}", req.getAttribute(AuthInterceptor.USER_ATTR));

            return ResponseEntity.status(401).body(Map.of("error", TOKEN_REJECTED, "tokenRejected", true));
        }

        log.warn("TeamCity answered {} to {} {}", status, req.getMethod(), req.getRequestURI());

        return ResponseEntity.status(502).body(Map.of("error", e instanceof QueueRefusedException ? QUEUE_FORBIDDEN
            : status == 403 ? FORBIDDEN : "TeamCity answered " + status + " — try again in a moment"));
    }

    /**
     * A request body that is not the JSON asked for. Jackson's message quotes the body, a token typed without quotes
     * included, so the log gets where the body broke and nothing of it. At INFO: a malformed body that anyone can send
     * to /api/login must not turn the service's health amber.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e,
        HttpServletRequest req) {
        log.info("Unreadable body of {} {}{}", req.getMethod(), req.getRequestURI(), whereBroken(e));

        return ResponseEntity.badRequest().body(Map.of("error", BAD_JSON));
    }

    /**
     * A query or a form body that Tomcat cannot read: a broken %-escape, or bytes that are not UTF-8. Tomcat's message
     * quotes the parameter, a token in a form body included, so the log gets the request and nothing of it. At INFO:
     * anyone can send such a request, and it must not count as an error of the service.
     */
    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<Map<String, Object>> unreadableParameters(InvalidParameterException e,
        HttpServletRequest req) {
        log.info("Unreadable parameters of {} {}", req.getMethod(), req.getRequestURI());

        return ResponseEntity.badRequest().body(Map.of("error", BAD_PARAMETERS));
    }

    private static String whereBroken(HttpMessageNotReadableException e) {
        if (!(e.getCause() instanceof JacksonException json) || json.getLocation() == null)
            return "";

        return " at line " + json.getLocation().getLineNr() + ", column " + json.getLocation().getColumnNr();
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

            return ResponseEntity.status(502).body(Map.of("error", e.cancelled() == null
                ? PREVIOUS_RUNALL_CANCELLED + " — try RunAll again" : e.cancelled() + " — press Run again",
                "replaced", e.replaced()));
        }

        Map<String, Object> body = new HashMap<>(alone.getBody());
        body.put("error", (e.cancelled() == null ? PREVIOUS_RUNALL_CANCELLED : e.cancelled()) + ": " + body.get("error"));
        body.put("replaced", e.replaced());

        return ResponseEntity.status(alone.getStatusCode()).body(body);
    }
}
