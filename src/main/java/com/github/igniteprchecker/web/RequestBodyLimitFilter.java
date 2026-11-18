package com.github.igniteprchecker.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request body no endpoint has a use for. The bodies the checker reads hold a token or two, or the ids of
 * some logins; anyone can post one to /api/login without a login, and Jackson reads a string of up to 20 million
 * characters into a heap of 512 MB, so a few such bodies at once ended the service. A body that names its length is
 * refused by it; one sent in chunks is read up to the limit.
 */
@Component
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BYTES = 64 * 1024;

    static final String REFUSED = "{\"error\":\"Request body too large\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        if (req.getContentLengthLong() > MAX_BYTES) {
            res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write(REFUSED);

            return;
        }

        chain.doFilter(req.getContentLengthLong() < 0 ? new UpToTheLimit(req) : req, res);
    }

    /** A request whose body ends with an error once it has given {@link #MAX_BYTES}. */
    private static final class UpToTheLimit extends HttpServletRequestWrapper {
        UpToTheLimit(HttpServletRequest req) {
            super(req);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();

            return new ServletInputStream() {
                private int given;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++given > MAX_BYTES)
                        throw new IOException("Request body too large");

                    return b;
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
