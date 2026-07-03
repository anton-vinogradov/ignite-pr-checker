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
        BuildType buildType,
        @JsonProperty("snapshot-dependencies") SnapshotDeps snapshotDependencies
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildType(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotDeps(int count, List<Build> build) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestOccurrences(int count, List<TestOccurrence> testOccurrence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestOccurrence(String id, String name, String status, TestRef test, BuildRef build) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestRef(long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildRef(long id, String branchName, Changes changes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Changes(int count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String username) {
    }
}
