package com.kbprasac.chatbot.config;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Botpress Desk mode (the default): the built-in agent console page is kept in the code but not served.
 * Only the console page is hidden; /agent/file.html stays, because Desk messages link to it for customer files.
 * Set app.handover.inbox=console to bring the console back.
 */
@Component
@ConditionalOnProperty(name = "app.handover.inbox", havingValue = "botpress-desk", matchIfMissing = true)
public class ConsolePageFilter extends OncePerRequestFilter {

    private static final Set<String> CONSOLE_PAGES = Set.of("/agent", "/agent/", "/agent/index.html");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CONSOLE_PAGES.contains(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
