package com.kbprasac.chatbot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConsolePageFilterTest {

    private final ConsolePageFilter filter = new ConsolePageFilter();

    @ParameterizedTest
    @ValueSource(strings = {"/agent", "/agent/", "/agent/index.html"})
    void hidesTheConsolePage(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
        assertEquals(404, response.getStatus());
        assertNull(chain.getRequest(), "the console page must not be served");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/agent/file.html", "/api/v1/agent/handovers/1/attachments/2", "/api/v1/chat/config"})
    void keepsTheFilePageAndApis(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "Desk links to the file page; it must stay");
    }
}
