package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@NovaComponent
class QqBotImageDownloader {
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client;

    QqBotImageDownloader() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    QqBotImageDownloader(HttpClient client) {
        this.client = client;
    }

    byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        } catch (IllegalArgumentException e) {
            throw new IOException("图片地址无效");
        }

        var exchange = client.sendAsync(request, response -> new LimitedBody(
                response.headers().firstValueAsLong("Content-Length").orElse(0)));
        try {
            // 请求的超时也覆盖正文接收，避免已收到响应头的连接一直占用发送线程。
            var response = exchange.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            throw new IOException("图片下载失败");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TooLarge tooLarge) {
                throw tooLarge;
            }
            throw new IOException("图片下载失败");
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new IOException("图片下载超时");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            throw e;
        }
    }

    static final class TooLarge extends IOException {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private final long contentLength;
        private Flow.Subscription subscription;
        private long remaining = MAX_BYTES;
        private boolean tooLarge;

        LimitedBody(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
            if (contentLength > MAX_BYTES) {
                reject();
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (tooLarge) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                remaining -= buffer.remaining();
                if (remaining < 0) {
                    reject();
                    return;
                }
            }
            body.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            body.onError(error);
        }

        @Override
        public void onComplete() {
            body.onComplete();
        }

        private void reject() {
            tooLarge = true;
            subscription.cancel();
            body.onError(new TooLarge());
        }
    }
}
