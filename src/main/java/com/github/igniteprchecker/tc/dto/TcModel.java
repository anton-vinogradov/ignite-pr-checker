package com.github.igniteprchecker.tc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Minimal DTOs for the slices of the TeamCity REST JSON we actually read.
 * Unknown fields are ignored (TeamCity returns far more than we map).
 */
public final class TcModel {
    /** The build parameter holding the JDK a build ran on. */
    public static final String JAVA_HOME = "env.JAVA_HOME";

    /** The build parameter Ignite tests scale their workloads by. */
    public static final String TEST_SCALE_FACTOR = "TEST_SCALE_FACTOR";

    /**
     * The build parameter that the first step of the Python thin client's suite checks the client out at, over the
     * commit TeamCity picked: without it the suite tests the client's master.
     */
    public static final String OVERRIDDEN_BRANCH = "OVERRIDDEN_BRANCH";

    private TcModel() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildList(List<Build> build) {
    }

    /** Builds with the few parameters asked for by name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildRefs(List<BuildRef> build) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Build(
        long id,
        String status,
        String state,
        String branchName,
        String buildTypeId,
        String webUrl,
        String queuedDate,
        String startDate,
        String finishDate,
        String startEstimate,
        String finishEstimate,
        BuildType buildType,
        Triggered triggered,
        @JsonProperty("snapshot-dependencies") SnapshotDeps snapshotDependencies,
        @JsonProperty("running-info") RunningInfo runningInfo,
        ProblemOccurrences problemOccurrences,
        Revisions revisions,
        TestOccurrences testOccurrences,
        CanceledInfo canceledInfo
    ) {
        /** A build without its cancellation, in the shape callers used before it was read. */
        public Build(long id, String status, String state, String branchName, String buildTypeId, String webUrl,
            String queuedDate, String startDate, String finishDate, String startEstimate, String finishEstimate,
            BuildType buildType, Triggered triggered, SnapshotDeps snapshotDependencies, RunningInfo runningInfo,
            ProblemOccurrences problemOccurrences, Revisions revisions, TestOccurrences testOccurrences) {
            this(id, status, state, branchName, buildTypeId, webUrl, queuedDate, startDate, finishDate, startEstimate,
                finishEstimate, buildType, triggered, snapshotDependencies, runningInfo, problemOccurrences, revisions,
                testOccurrences, null);
        }
    }

    /**
     * Why and by whom a build was cancelled. {@code user} is absent when TeamCity cancelled it by itself,
     * e.g. with "Build revision not found".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CanceledInfo(String text, User user) {
    }

    /** The VCS revision(s) a build was run on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Revisions(List<Revision> revision) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Revision(String version) {
    }

    /** Builds with the VCS root instance of each of their commits. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootedBuilds(List<RootedBuild> build) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootedBuild(RootedRevisions revisions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootedRevisions(List<RootedRevision> revision) {
    }

    /** A commit of a build, by the VCS root instance it came from; {@code version} is null when not asked for. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootedRevision(String version, @JsonProperty("vcs-root-instance") RootInstance vcsRootInstance) {
    }

    /** Finished runs of a suite on a branch. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchRuns(List<BranchRun> build) {
    }

    /**
     * A finished run of a suite on a branch: the suite's name, who started it, the commit of each of its VCS roots,
     * and the build parameters asked for by name.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchRun(long id, BuildType buildType, Triggered triggered, RootedRevisions revisions,
        Properties resultingProperties) {
    }

    /** VCS root instances, as TeamCity lists them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootInstances(@JsonProperty("vcs-root-instance") List<RootInstance> vcsRootInstance) {
    }

    /** A VCS root of a build type, with the build type's parameters resolved: its own id, and its VCS root's id. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootInstance(String id, @JsonProperty("vcs-root-id") String vcsRootId) {
    }

    /** Live progress of a running build, as TeamCity estimates it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunningInfo(Integer percentageComplete, Long elapsedSeconds, Long estimatedTotalSeconds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildType(String id, String name) {
    }

    /** Who/what started a build. {@code type} is e.g. "user" or "vcs"; {@code user} is set when a person triggered it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Triggered(String type, User user) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotDeps(int count, List<Build> build) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestOccurrences(int count, List<TestOccurrence> testOccurrence) {
    }

    /**
     * One run of a test; {@code duration} is in milliseconds. {@code muted}: TeamCity muted the test in that run, so
     * its failure did not fail the build. Each is null when not asked for, and then left out of the runs kept on disk.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestOccurrence(String id, String name, String status, TestRef test, BuildRef build, String details,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long duration,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean muted) {
        /** A run without its duration, in the shape callers used before it was read. */
        public TestOccurrence(String id, String name, String status, TestRef test, BuildRef build, String details) {
            this(id, name, status, test, build, details, null, null);
        }

        /** A run without whether it was muted, in the shape callers used before that was read. */
        public TestOccurrence(String id, String name, String status, TestRef test, BuildRef build, String details,
            Long duration) {
            this(id, name, status, test, build, details, duration, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestRef(long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterOccurrences(List<MasterOccurrence> testOccurrence) {
    }

    /** One master run of a test, with whether the test is muted now (not when it ran). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterOccurrence(String id, String status, Boolean currentlyMuted, BuildRef build) {
    }

    /**
     * The build a test occurrence came from. {@code revisions} is the VCS state that build ran on —
     * the only honest way to tell whether two runs of a test were made on the same code.
     * {@code resultingProperties} holds the few build parameters asked for by name, such as the JDK.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildRef(long id, String branchName, String state, String status,
        String buildTypeId, BuildType buildType, Revisions revisions, Properties resultingProperties) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(List<Property> property) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Property(String name, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String username) {
    }

    /** The access tokens of a user, without their values. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tokens(List<Token> token) {
    }

    /** An access token: {@code expirationTime} is null for a token that never expires. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Token(String name, String expirationTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProblemOccurrences(List<ProblemOccurrence> problemOccurrence) {
    }

    /** A build-level problem: {@code type} like TC_COMPILATION_ERROR / TC_EXECUTION_TIMEOUT, plus details. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProblemOccurrence(String type, String details) {
    }
}
