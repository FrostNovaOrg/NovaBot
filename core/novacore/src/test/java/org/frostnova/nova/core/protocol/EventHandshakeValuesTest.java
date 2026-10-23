package org.frostnova.nova.core.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventHandshakeValuesTest {
    @ParameterizedTest
    @ValueSource(strings = {"token=x", "access_token=x", "id_token=x", "refresh_token=x",
            "password=x", "secret=x", "authorization=x", "credential=x", "api_key=x",
            "TOKEN=x", "%74oken=x", "view=main&token", "token=", "view=main;token=x",
            "access-token=x", "apiKey=x", "readonly_token=x", "client_secret=x", "credentials=x", "auth=x"})
    void requestValues(String query) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(evaluate(query, response));
        assertEquals(400, response.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "view=main", "room=1&view=main", "label=%E4%B8%AD%E6%96%87",
            "view=token", "tokenizer=main", "view=a%26token%3Db", "view=a=b"})
    void ordinaryValues(String query) {
        assertTrue(evaluate(query, new MockHttpServletResponse()));
    }

    @Test
    void unreadableRequestValue() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        URI uri = mock(URI.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getURI()).thenReturn(uri);
        when(uri.getRawQuery()).thenReturn("view%");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(new NoCredentialsInHandshake().beforeHandshake(request,
                new ServletServerHttpResponse(response), null, new HashMap<>()));
        assertEquals(400, response.getStatus());
    }

    private boolean evaluate(String query, MockHttpServletResponse response) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getURI()).thenReturn(URI.create("http://127.0.0.1:7827/nova/events"
                + (query.isEmpty() ? "" : "?" + query)));
        return new NoCredentialsInHandshake().beforeHandshake(request,
                new ServletServerHttpResponse(response), null, new HashMap<>());
    }
}
