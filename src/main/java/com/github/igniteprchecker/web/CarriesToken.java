package com.github.igniteprchecker.web;

import java.util.Collections;
import java.util.List;

/** A request body with a token the user pasted; {@link TokenFormat} checks it before the controller sees it. */
interface CarriesToken {
    /** The token as pasted; null or blank when the request brings none. */
    String token();

    /** Every token the request brings, as pasted, nulls included; {@link #token} alone for a body with one. */
    default List<String> tokens() {
        return Collections.singletonList(token());
    }
}
