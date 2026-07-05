package com.github.igniteprchecker.tc;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parsing for TeamCity's {@code yyyyMMdd'T'HHmmssZ} timestamps. */
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
}
