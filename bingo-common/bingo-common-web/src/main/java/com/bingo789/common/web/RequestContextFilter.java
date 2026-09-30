package com.bingo789.common.web;

import com.bingo789.common.core.HeaderNames;
import com.bingo789.common.core.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates trace id and {@link CurrentUser} from gateway headers and echoes the trace id back.
 */
public class RequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HeaderNames.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.set(traceId);
        response.setHeader(HeaderNames.TRACE_ID, traceId);

        CurrentUser.set(new CurrentUser(
                parseLong(request.getHeader(HeaderNames.USER_ID)),
                request.getHeader(HeaderNames.SESSION_ID),
                firstNonBlank(request.getHeader(HeaderNames.CLIENT_IP), request.getRemoteAddr()),
                request.getHeader(HeaderNames.DEVICE_ID),
                request.getHeader(HeaderNames.COUNTRY_CODE)));
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
            TraceContext.clear();
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
