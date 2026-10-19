package com.github.igniteprchecker;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A mapper as {@code new ObjectMapper()} of Jackson 2 was, which the tests that build parts of the service by hand pass
 * where the service passes its own. Jackson 3's own defaults refuse a file without a field its record has since, which
 * the service, keeping Jackson 2's, reads.
 */
public final class Jackson2Defaults {
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private Jackson2Defaults() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
