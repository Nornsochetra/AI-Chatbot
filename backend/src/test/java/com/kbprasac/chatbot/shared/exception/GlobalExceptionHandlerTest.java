package com.kbprasac.chatbot.shared.exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void recognisesClosedBrowserConnections() {
        // the Windows message from a closed tab during a live-update push
        assertTrue(GlobalExceptionHandler.isClientDisconnect(
                new IOException("An established connection was aborted by the software in your host machine")));
        assertTrue(GlobalExceptionHandler.isClientDisconnect(new RuntimeException(new IOException("Broken pipe"))));
        assertTrue(GlobalExceptionHandler.isClientDisconnect(new IOException("Connection reset by peer")));
        assertFalse(GlobalExceptionHandler.isClientDisconnect(new IOException("Disk full")));
        assertFalse(GlobalExceptionHandler.isClientDisconnect(new IllegalStateException("boom")));
    }

    @Test
    void writesNothingForAClosedConnectionOrALiveUpdateStream() {
        assertNull(handler.handleUnexpected(new IOException("An established connection was aborted"), new MockHttpServletResponse()));

        MockHttpServletResponse stream = new MockHttpServletResponse();
        stream.setContentType("text/event-stream");
        assertNull(handler.handleUnexpected(new IllegalStateException("boom"), stream));
    }

    @Test
    void realErrorsStillReturn500() {
        assertNotNull(handler.handleUnexpected(new IllegalStateException("boom"), new MockHttpServletResponse()));
    }
}
