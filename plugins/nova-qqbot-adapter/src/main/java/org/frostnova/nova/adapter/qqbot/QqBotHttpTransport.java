package org.frostnova.nova.adapter.qqbot;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

interface QqBotHttpTransport {
    URI ORIGIN = URI.create("https://api.bot.qq.com");

    Response send(String method, URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException;

    record Response(int status, String body) {
        @Override
        public String toString() {
            return "HTTP " + status;
        }
    }
}
