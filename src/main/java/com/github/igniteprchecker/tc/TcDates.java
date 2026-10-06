package com.github.igniteprchecker.tc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parsing and formatting of TeamCity's {@code yyyyMMdd'T'HHmmssZ} timestamps. */
public final class TcDates {
    private static final DateTimeFormatter TC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssZ");

    private TcDates() {
    }

    /** The timestamp as epoch seconds, or 0 when absent/unparsable. */
    public static long epochSeconds(String tcDate) {
        if (tcDate == null || tcDate.isBlank())
            return 0;

        try {
            return OffsetDateTime.parse(tcDate, TC_DATE).toEpochSecond();
        }
        catch (DateTimeParseException e) {
            return 0;
        }
    }

    /** Epoch seconds as a TeamCity timestamp in UTC, for locator dimensions such as {@code finishDate}. */
    public static String format(long epochSeconds) {
        return TC_DATE.format(Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC));
    }
}
