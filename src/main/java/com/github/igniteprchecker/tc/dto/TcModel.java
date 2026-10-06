package com.github.igniteprchecker.tc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Minimal DTOs for the slices of the TeamCity REST JSON we actually read.
 * Unknown fields are ignored (TeamCity returns far more than we map).
 */
public final class TcModel {
    private TcModel() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildList(List<Build> build) {
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
        TestOccurrences testOccurrences
    ) {
    }

    /** The VCS revision(s) a build was run on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Revisions(List<Revision> revision) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Revision(String version) {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestOccurrence(String id, String name, String status, TestRef test, BuildRef build, String details) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestRef(long id) {
    }

    /**
     * The build a test occurrence came from. {@code revisions} is the VCS state that build ran on —
     * the only honest way to tell whether two runs of a test were made on the same code.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildRef(long id, String branchName, String state, String status,
        String buildTypeId, BuildType buildType, Revisions revisions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String username) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProblemOccurrences(List<ProblemOccurrence> problemOccurrence) {
    }

    /** A build-level problem: {@code type} like TC_COMPILATION_ERROR / TC_EXECUTION_TIMEOUT, plus details. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProblemOccurrence(String type, String details) {
    }
}
