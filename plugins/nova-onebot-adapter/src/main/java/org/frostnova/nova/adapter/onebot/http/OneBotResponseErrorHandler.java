package org.frostnova.nova.adapter.onebot.http;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResponseErrorHandler;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;

/** 在 OneBot 发送接口上保留读取 HTTP 错误回应时的断线异常。 */
public final class OneBotResponseErrorHandler extends DefaultResponseErrorHandler {
    private final ResponseErrorHandler otherActions;

    public OneBotResponseErrorHandler(ResponseErrorHandler otherActions) {
        this.otherActions = otherActions;
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        if (url.getPath().endsWith("/send_group_msg") || url.getPath().endsWith("/send_private_msg")) {
            super.handleError(url, method, response);
        } else {
            otherActions.handleError(url, method, response);
        }
    }

    @Override
    protected byte[] getResponseBody(ClientHttpResponse response) {
        try {
            return FileCopyUtils.copyToByteArray(response.getBody());
        } catch (IOException e) {
            // 默认处理器把读取错误页时的异常都当作空正文。断线须交给发送方，其他错误照常按状态码处理。
            if (missingReplyReason(e) != null) throw new UncheckedIOException(e);
            return new byte[0];
        }
    }

    /** 调用方已排除建连失败。这里只识别传输中断，完整回应仍由原来的状态码和正文处理。 */
    public static String missingReplyReason(Throwable error) {
        boolean handshake = false;
        boolean disconnected = false;
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return "请求已送出, 等回包超时";
            }
            handshake |= current instanceof SSLHandshakeException;
            disconnected |= current instanceof IOException io && connectionClosed(io);
            current = current.getCause();
        }
        return disconnected && !handshake ? "请求已送出, 读取回包时连接断开" : null;
    }

    private static boolean connectionClosed(IOException io) {
        if (io instanceof ClosedChannelException) return true;
        if (io instanceof EOFException) {
            // 完整 HTTP 正文中的压缩数据也可能缺尾。解压器自己抛的 EOF 不能证明连接中断。
            StackTraceElement[] trace = io.getStackTrace();
            return trace.length > 0 && !trace[0].getClassName().startsWith("java.util.zip.");
        }
        if (io.getMessage() == null) return false;
        String reason = io.getMessage().toLowerCase(Locale.ROOT);
        if (io instanceof SocketException) {
            return reason.contains("reset") || reason.contains("closed") || reason.contains("abort");
        }
        return reason.equals("closed") || reason.contains("connection reset") || reason.contains("socket closed")
                || reason.contains("connection closed") || reason.contains("connection aborted")
                || reason.contains("forcibly closed") || reason.contains("header parser received no bytes")
                || reason.contains("unexpected end of file from server");
    }
}
