package com.github.igniteprchecker.config;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON by Jackson 2's rules and limits, for the mappers the service builds itself. Spring Boot's mapper gets the same
 * from spring.jackson in application.yml.
 */
public final class Jackson2Json {
    /**
     * Jackson 2.21's limits of a read. Jackson 3 reads a string of up to 100 million characters, which takes hundreds
     * of megabytes of the heap, and refuses JSON nested more than 500 deep, which Jackson 2 read up to 1000.
     */
    private static final StreamReadConstraints READ = StreamReadConstraints.builder()
        .maxStringLength(20_000_000)
        .maxNestingDepth(1000)
        .build();

    /** Jackson 2.21's limit of a write: what {@link #READ} lets in can be written back. */
    private static final StreamWriteConstraints WRITE = StreamWriteConstraints.builder()
        .maxNestingDepth(1000)
        .build();

    private Jackson2Json() {
    }

    public static JsonMapper.Builder builder() {
        JsonFactory factory = JsonFactory.builderWithJackson2Defaults()
            .streamReadConstraints(READ)
            .streamWriteConstraints(WRITE)
            .build();

        return JsonMapper.builder(factory).configureForJackson2();
    }
}
