package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.util.MessageImageAddressPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

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

/**
 * 下载消息里的网络图片
 * <p>
 * 每一跳之前都按 {@link MessageImageAddressPolicy} 判地址：指向本机、内网或链路本地的不发起任何连接。
 * 客户端不自动跟随跳转，这里一跳一跳地跟，最多 {@link #MAX_REDIRECTS} 跳，每一跳的新地址重新判；
 * 从 https 跳到 http 的不跟。
 * <p>
 * 跳转响应的正文照常收下丢掉，最多收 {@link #MAX_REDIRECT_BODY_BYTES}，超过就断开、这一跳按下载失败算。
 * 不在收正文之前就断开：HTTP/2 下断开会让这次响应直接失败，连状态码和新地址都拿不到。
 * 图片超过 {@link #MAX_BYTES} 时断开，不管协议层把这次响应报成什么错，都按图片太大报。
 * <p>
 * 判地址时解析了一次主机名，客户端连接时会再解析一次；两次之间解析结果变了，这一道看不出来，本程序不处理这个窗口。
 */
@NovaComponent
class QqBotImageDownloader {
    static final int MAX_BYTES = 20 * 1024 * 1024;
    /** 最多跟几跳：图床、CDN 常见一两跳 */
    static final int MAX_REDIRECTS = 3;
    /** 跳转响应的正文最多收这么多：正常的跳转正文只有一小段说明 */
    static final int MAX_REDIRECT_BODY_BYTES = 64 * 1024;
    static final String BLOCKED = "图片地址指向本机或内网，未下载";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client;
    private final MessageImageAddressPolicy policy;

    /**
     * 插件单独装起来（不带核心）时没有核心的那一件，就按不放行任何内网网段判
     */
    @Autowired
    QqBotImageDownloader(ObjectProvider<MessageImageAddressPolicy> policy) {
        this(policy.getIfAvailable(MessageImageAddressPolicy::new));
    }

    /** 正式用的客户端，按给定的地址规则判 */
    QqBotImageDownloader(MessageImageAddressPolicy policy) {
        this(newClient(), policy);
    }

    QqBotImageDownloader(HttpClient client) {
        this(client, new MessageImageAddressPolicy());
    }

    QqBotImageDownloader(HttpClient client, MessageImageAddressPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    /** 客户端不自动跟跳转：每一跳都要先过地址检查 */
    private static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpClient client() {
        return client;
    }

    byte[] download(String url) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("图片地址无效");
        }
        admit(uri, "图片地址无效");

        // 请求的超时也覆盖正文接收与之后的每一跳，避免已收到响应头的连接一直占用发送线程。
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        for (int redirects = 0; ; redirects++) {
            var response = fetch(uri, deadline);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response.body();
            }
            if (!redirect(status) || redirects == MAX_REDIRECTS) {
                throw new IOException("图片下载失败");
            }
            uri = next(uri, response);
        }
    }

    /**
     * 地址不许取时不发起连接
     * @param invalid 地址写得不对时的说法
     */
    private void admit(URI uri, String invalid) throws IOException {
        switch (policy.check(uri)) {
            case ALLOWED -> { }
            case BLOCKED -> throw new Blocked();
            case INVALID -> throw new IOException(invalid);
            case UNRESOLVED -> throw new IOException("图片下载失败");
        }
    }

    private HttpResponse<byte[]> fetch(URI uri, long deadline) throws IOException, InterruptedException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        } catch (IllegalArgumentException e) {
            throw new IOException("图片地址无效");
        }
        var body = new Body();
        var exchange = client.sendAsync(request, body);
        try {
            return exchange.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            // 图片超限时这边断开了：HTTP/2 下协议层会因断开另报一个错，而且常常先到，所以看这边记下的
            if (body.tooLarge()) {
                throw new TooLarge();
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

    /**
     * 跳转的新地址：相对地址按当前地址补全，新地址照样要判，从 https 跳到 http 的不跟
     */
    private URI next(URI current, HttpResponse<?> response) throws IOException {
        String location = response.headers().firstValue("Location").orElse("").strip();
        if (location.isEmpty()) {
            throw new IOException("图片下载失败");
        }
        URI target;
        try {
            target = current.resolve(location);
            if (target.getRawFragment() != null) {
                String text = target.toString();
                target = URI.create(text.substring(0, text.indexOf('#')));
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("图片下载失败");
        }
        // 先判地址：跳到内网的那一跳，失败原因写清是内网，而不是笼统的下载失败
        admit(target, "图片下载失败");
        if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
            throw new IOException("图片下载失败");
        }
        return target;
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static final class TooLarge extends IOException {}

    /** 图片地址指向本机、内网或链路本地 */
    static final class Blocked extends IOException {
        Blocked() {
            super(BLOCKED);
        }
    }

    /**
     * 一跳的响应正文怎么收：跳转的正文收下丢掉，最多 {@link #MAX_REDIRECT_BODY_BYTES}；
     * 别的照收，最多 {@link #MAX_BYTES}
     */
    private static final class Body implements HttpResponse.BodyHandler<byte[]> {
        private volatile Limited image;

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo response) {
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
            if (redirect(response.statusCode())) {
                return new Limited(HttpResponse.BodySubscribers.<byte[]>replacing(null), MAX_REDIRECT_BODY_BYTES,
                        declared);
            }
            Limited body = new Limited(HttpResponse.BodySubscribers.ofByteArray(), MAX_BYTES, declared);
            image = body;
            return body;
        }

        /** 图片超过上限、这边断开了 */
        boolean tooLarge() {
            Limited body = image;
            return body != null && body.exceeded;
        }
    }

    /** 正文超过上限就断开，不再收；没超过的交给 {@code body} */
    private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body;
        private final long limit;
        private final long declared;
        private Flow.Subscription subscription;
        private long received;
        private volatile boolean exceeded;

        Limited(HttpResponse.BodySubscriber<byte[]> body, long limit, long declared) {
            this.body = body;
            this.limit = limit;
            this.declared = declared;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
            if (declared > limit) {
                stop();
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (exceeded) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
            }
            if (received > limit) {
                stop();
                return;
            }
            body.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            body.onError(error);
        }

        @Override
        public void onComplete() {
            if (!exceeded) {
                body.onComplete();
            }
        }

        /** 先记下超限再断开：断开时这次响应可能当场就判成失败了 */
        private void stop() {
            exceeded = true;
            subscription.cancel();
            body.onError(new IOException("正文超过上限"));
        }
    }
}
