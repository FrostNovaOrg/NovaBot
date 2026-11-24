package org.frostnova.nova.adapter.qqbot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

interface QqBotHttpTransport {
    URI ORIGIN = URI.create("https://api.bot.qq.com");

    Response send(String method, URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException;

    default Response send(HttpRequest request) throws IOException, InterruptedException {
        throw new IOException("当前 HTTP 传输不支持分片上传");
    }

    record Response(int status, String body) {
        @Override
        public String toString() {
            return "HTTP " + status;
        }
    }
}
