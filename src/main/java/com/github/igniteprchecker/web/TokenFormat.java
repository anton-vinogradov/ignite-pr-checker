package com.github.igniteprchecker.web;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Refuses a pasted token with characters no TeamCity, JIRA or GitHub token has, before any service sees it. The JDK's
 * HTTP client refuses a header with a line break and names the header in its error, {@code Bearer <token>} included,
 * and the error goes to the log. The refusal does not repeat the value.
 */
@ControllerAdvice
public class TokenFormat extends RequestBodyAdviceAdapter {
    /** Letters, digits and the signs of base64 and of TeamCity's dotted tokens, at a token's length. */
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{20,512}");

    static final String NOT_A_TOKEN = "This is not a token: a token is 20 to 512 letters, digits and the characters "
        + ". _ ~ + / = -, with no spaces or line breaks";

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType instanceof Class<?> type && CarriesToken.class.isAssignableFrom(type);
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType) {
        if (body instanceof CarriesToken carried && carried.token() != null && !carried.token().isBlank()
            && !TOKEN.matcher(carried.token().strip()).matches())
            throw new Malformed();

        return body;
    }

    @ExceptionHandler(Malformed.class)
    public ResponseEntity<Map<String, Object>> refused() {
        return ResponseEntity.badRequest().body(Map.of("error", NOT_A_TOKEN));
    }

    /** A pasted value that cannot be a token. */
    static final class Malformed extends RuntimeException {
        Malformed() {
            super("not a token", null, false, false);
        }
    }
}
