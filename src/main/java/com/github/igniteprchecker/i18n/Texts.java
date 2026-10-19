package com.github.igniteprchecker.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The words the server writes for the pages, in the language of their reader. The Russian comes from the catalog the
 * pages use, {@code static/ru.json}: its sections map each English string to its Russian, so a phrase reads the same
 * wherever it is shown. A key names its values by their place, {0}, {1}; the Russian may take the form of a word that
 * goes with a number, {0|тест|теста|тестов}. A string the catalog lacks stays English.
 */
public final class Texts {
    /** Where the catalog is, on the classpath; the pages get it as /ru.json. */
    public static final String CATALOG = "static/ru.json";

    private static final Pattern VALUE = Pattern.compile("\\{(\\d+)(?:\\|([^{}]*))?}");

    private static final Map<String, String> RU = load();

    private Texts() {
    }

    /** {@code key}, in English or in its Russian, with its values put in. */
    public static String t(Lang lang, String key, Object... values) {
        return fill(lang == Lang.RU ? RU.getOrDefault(key, key) : key, values);
    }

    private static String fill(String text, Object[] values) {
        Matcher m = VALUE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            String value = i < values.length ? String.valueOf(values[i]) : m.group();
            if (m.group(2) != null && i < values.length)
                value = form(values[i], m.group(2).split("\\|", -1));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);

        return out.toString();
    }

    /** The form of a Russian word that goes with the number: 1 тест, 2 теста, 5 тестов, 21 тест. */
    static String form(Object number, String[] forms) {
        long n = number instanceof Number num ? Math.abs(num.longValue()) : -1;
        if (n < 0 || forms.length < 3)
            return forms[forms.length - 1];

        long tens = n % 100;
        long ones = tens % 10;
        if (tens >= 11 && tens <= 14)
            return forms[2];

        return ones == 1 ? forms[0] : ones >= 2 && ones <= 4 ? forms[1] : forms[2];
    }

    private static Map<String, String> load() {
        try (InputStream in = Texts.class.getClassLoader().getResourceAsStream(CATALOG)) {
            if (in == null)
                return Map.of();

            Map<String, Map<String, String>> sections = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            Map<String, String> all = new HashMap<>();
            sections.values().forEach(all::putAll);

            return Map.copyOf(all);
        }
        catch (IOException e) {
            throw new UncheckedIOException("cannot read " + CATALOG, e);
        }
    }
}
