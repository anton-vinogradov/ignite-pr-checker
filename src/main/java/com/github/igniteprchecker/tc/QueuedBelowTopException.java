package com.github.igniteprchecker.tc;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.function.Supplier;
import org.springframework.web.client.RestClientResponseException;

/**
 * TeamCity queued the build but did not move it to the top of the queue, as it was asked. On ci2 only some accounts
 * may reorder the queue, and TeamCity queues a build before it moves it, so a refused move answers with an error while
 * the build already waits at the end of the queue. Carries that build: the caller counts it as queued, says where it
 * went and does not queue it again.
 */
public class QueuedBelowTopException extends RuntimeException {
    private final transient TcModel.Build build;

    private final int status;

    public QueuedBelowTopException(TcModel.Build build, RestClientResponseException refusal) {
        super("build " + build.id() + " was queued, but not at the top: " + refusal.getMessage(), refusal);
        this.build = build;
        this.status = refusal.getStatusCode().value();
    }

    /** The build TeamCity queued. */
    public TcModel.Build build() {
        return build;
    }

    /** The HTTP status TeamCity answered the request to queue it at the top with. */
    public int status() {
        return status;
    }

    /** The build {@code trigger} queued, at the top of the queue or not. */
    public static TcModel.Build queued(Supplier<TcModel.Build> trigger) {
        try {
            return trigger.get();
        }
        catch (QueuedBelowTopException e) {
            return e.build();
        }
    }
}
