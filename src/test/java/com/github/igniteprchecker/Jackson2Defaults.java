package com.github.igniteprchecker;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper that {@code new ObjectMapper()} was in Jackson 2. Tests that build parts of the service by hand pass it
 * where the service passes its own, which keeps Jackson 2's rules too. With Jackson 3's own defaults those parts fail
 * on a state file that lacks a field its record has.
 */
public final class Jackson2Defaults {
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private Jackson2Defaults() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
