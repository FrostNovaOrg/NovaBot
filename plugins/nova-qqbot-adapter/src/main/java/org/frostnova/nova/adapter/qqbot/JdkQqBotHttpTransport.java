package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@NovaComponent
class JdkQqBotHttpTransport implements QqBotHttpTransport, QqBotWebSocketTransport {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).method(method, publisher);
        headers.forEach(request::header);
        return send(request.build());
    }

    @Override
    public Response send(HttpRequest request) throws IOException, InterruptedException {
        Duration timeout = request.timeout().orElseThrow();
        var publisher = request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody());
        AtomicBoolean exchangeStarted = new AtomicBoolean();
        boolean hasBody = publisher.contentLength() > 0;
        var requestBody = hasBody ? HttpRequest.BodyPublishers.fromPublisher(subscriber -> {
            exchangeStarted.set(true);
            publisher.subscribe(subscriber);
        }, publisher.contentLength()) : publisher;
        var wireRequest = hasBody ? HttpRequest.newBuilder(request, (name, value) -> true)
                .method(request.method(), requestBody).build() : request;

        // 等到回包读完才算请求结束。
        var exchange = client.sendAsync(wireRequest, response -> {
            exchangeStarted.set(true);
            return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        });
        try {
            var response = exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            exchange.cancel(true);
            if (hasBody && !exchangeStarted.get()) {
                throw new HttpConnectTimeoutException("QQ 开放平台连接超时");
            }
            throw new HttpTimeoutException("QQ 开放平台请求超时");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("QQ 开放平台请求失败");
        }
    }

    @Override
    public Connection connect(URI uri, Listener listener) throws IOException, InterruptedException {
        var opening = client.newWebSocketBuilder().connectTimeout(REQUEST_TIMEOUT)
                .buildAsync(uri, new WebSocketListener(listener));
        try {
            return new WebSocketConnection(opening.get());
        } catch (ExecutionException e) {
            throw new IOException("QQ 开放平台长连接握手失败");
        } catch (InterruptedException e) {
            opening.thenAccept(WebSocket::abort);
            throw e;
        }
    }

    private static final class WebSocketConnection implements Connection {
        private final WebSocket socket;
        private CompletableFuture<?> sent = CompletableFuture.completedFuture(null);

        private WebSocketConnection(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public CompletionStage<?> send(String text) {
            // 同时只能有一条文本发送在途。
            sent = sent.thenCompose(ignored -> socket.sendText(text, true));
            return sent;
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private static final class WebSocketListener implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder text = new StringBuilder();

        private WebSocketListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                listener.onText(text.toString());
                text.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            listener.onClose(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            listener.onFailure();
        }
    }
}
