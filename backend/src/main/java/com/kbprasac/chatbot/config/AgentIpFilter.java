package com.kbprasac.chatbot.config;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * BRD internal access security: agent pages (/agent/**) and agent APIs (/api/v1/agent/**) only from the bank's IPs
 * ({@code app.agent.allowed-ips}). Runs before Spring Security, so other addresses get 403 without reaching the
 * login. Empty list = from anywhere (R&D).
 * Behind a reverse proxy the remote address is the proxy's: set server.forward-headers-strategy so the real client
 * address is used, and only when the proxy is trusted to set X-Forwarded-For.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentIpFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentIpFilter.class);

    private final List<IpAddressMatcher> allowed;

    public AgentIpFilter(AgentProperties agent) {
        this.allowed = agent.allowedIps().stream().map(IpAddressMatcher::new).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (allowed.isEmpty()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/agent") || path.startsWith("/agent/") || path.startsWith("/api/v1/agent/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        if (allowed.stream().anyMatch(m -> m.matches(ip))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Agent access refused from {} to {}", ip, request.getRequestURI());
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
