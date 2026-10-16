package com.github.igniteprchecker.web;

/** A request body with a token the user pasted; {@link TokenFormat} checks it before the controller sees it. */
interface CarriesToken {
    /** The token as pasted; null or blank when the request brings none. */
    String token();
}
