package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@NovaComponent
class JdkQqBotHttpTransport implements QqBotHttpTransport {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);

        // 等到回包读完才算请求结束。
        var exchange = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var response = exchange.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new HttpTimeoutException("QQ 开放平台请求超时");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("QQ 开放平台请求失败");
        }
    }
}
