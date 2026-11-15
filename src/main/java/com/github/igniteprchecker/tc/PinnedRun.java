package com.github.igniteprchecker.tc;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A run of one suite pinned to exact commits: the branch TeamCity files it under, the commit of each of its VCS roots,
 * and the build parameters it runs with. Queued again as it is, it runs the same code: a suite of a thin
 * client takes Ignite and the client from two VCS roots, and its first step checks out {@code OVERRIDDEN_BRANCH} of the
 * client over the commit TeamCity picked.
 *
 * @param suite      the build type.
 * @param branch     the branch TeamCity files the run under, such as {@code pull/69}.
 * @param revisions  the commit of each VCS root.
 * @param properties build parameters by name.
 */
public record PinnedRun(String suite, String branch, List<Pin> revisions, Map<String, String> properties) {
    public PinnedRun {
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** The commit of a VCS root, identified by its instance id or external root id. */
    public record Pin(String vcsRootInstance, String vcsBranch, String version,
        @JsonInclude(JsonInclude.Include.NON_NULL) String vcsRootId) {
        public Pin(String vcsRootInstance, String vcsBranch, String version) {
            this(vcsRootInstance, vcsBranch, version, null);
        }

        public static Pin forRoot(String vcsRootId, String vcsBranch, String version) {
            return new Pin(null, vcsBranch, version, vcsRootId);
        }
    }
}
