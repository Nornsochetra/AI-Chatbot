package com.kbprasac.chatbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AgentIpFilterTest {

    private final AgentIpFilter filter = filterAllowing(List.of(" 203.0.113.10 ", "10.0.0.0/8", ""));

    private static AgentIpFilter filterAllowing(List<String> ips) {
        return new AgentIpFilter(new AgentProperties("agent", "secret", ips));
    }

    private static MockHttpServletResponse call(AgentIpFilter filter, String path, String ip, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/agent/file.html", "/agent/message.html", "/agent", "/api/v1/agent/handovers/1/messages/2"})
    void refusesAgentPagesFromOtherAddresses(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(403, call(filter, path, "198.51.100.7", chain).getStatus());
        assertNull(chain.getRequest(), "must not reach the login or the page");
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.10", "10.20.30.40"})
    void letsAllowedAddressesAndRangesThrough(String ip) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, call(filter, "/agent/file.html", ip, chain).getStatus());
        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/chat/messages", "/api/v1/chat/config", "/actuator/health", "/agentx"})
    void customerEndpointsStayOpenToEveryone(String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, call(filter, path, "198.51.100.7", chain).getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void emptyListAllowsEveryone() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, call(filterAllowing(null), "/agent/file.html", "198.51.100.7", chain).getStatus());
        assertNotNull(chain.getRequest());
    }
}
