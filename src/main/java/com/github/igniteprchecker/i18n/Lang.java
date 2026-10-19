package com.github.igniteprchecker.i18n;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The language a user reads the pages in: English, unless they chose Russian in ⚙. PR comments, visas, logs and the
 * fields of the API stay English for everyone: the Ignite community and scripts read them.
 */
public enum Lang {
    EN("en"),
    RU("ru");

    private final String code;

    Lang(String code) {
        this.code = code;
    }

    /** The code the page and the files use: "en" or "ru". */
    @JsonValue
    public String code() {
        return code;
    }

    /** The language of a code; anything but "ru" is English. */
    public static Lang of(String code) {
        return RU.code.equals(code) ? RU : EN;
    }
}
