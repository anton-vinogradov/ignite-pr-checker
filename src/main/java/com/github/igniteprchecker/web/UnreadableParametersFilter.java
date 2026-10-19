package com.github.igniteprchecker.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 400 to a request whose query Tomcat cannot read, with a broken %-escape or bytes that are not UTF-8, when it
 * is read before the handlers. At DEBUG and TRACE Spring reads the parameters to log each request, and the exception
 * then misses {@link ApiExceptionHandler}, which gives the same answer when a handler reads them. Tomcat would log it as
 * an ERROR that quotes the parameter: an error of the service on the status page, and the parameter in the journal of
 * problems. The log line is at INFO: anyone can send such a request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnreadableParametersFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(UnreadableParametersFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        try {
            chain.doFilter(req, res);
        }
        catch (ServletException | RuntimeException e) {
            if (!unreadableParameters(e) || res.isCommitted())
                throw e;

            log.info("Unreadable parameters of {} {}", req.getMethod(), req.getRequestURI());
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"error\":\"" + ApiExceptionHandler.BAD_PARAMETERS + "\"}");
        }
    }

    /** Spring passes on what was thrown in it wrapped in a ServletException. */
    private static boolean unreadableParameters(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InvalidParameterException)
                return true;
        }

        return false;
    }
}
