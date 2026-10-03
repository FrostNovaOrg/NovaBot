package org.frostnova.nova.core.alert;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * 当前环境能不能在本机回环地址上绑一个端口再连上去。
 * <p>
 * 各模块的测试源码互相看不见，同一份探法各放一份；本模块里跨包共用 alert 包这一份。探一次，结果留到这次运行结束。
 * 只有绑或连报了权限类错误，才当作环境不许；别的异常原样抛出。
 */
public final class LoopbackPort {
    private static final String SKIPPED =
            "当前环境不许用本机回环端口，跳过；不受限的环境里照常跑";

    private static volatile Outcome outcome;

    /** 空串表示已经探过、连 1 号端口是当场被拒；非空是跳过原因。 */
    private static volatile String portOneSkip;

    private LoopbackPort() {
    }

    public static void assumeAllowed() {
        Outcome current = outcome();
        if (current.error != null) {
            sneakyThrow(current.error);
        }
        Assumptions.assumeTrue(current.allowed, SKIPPED);
    }

    /**
     * 连 127.0.0.1 的 1 号端口。当场被拒才继续；有人在听，或出了别的失败，就跳过并写明原因。
     * 一次运行只探一次。
     */
    public static void assumePortOneRefused() {
        String skip = portOneSkipReason();
        if (skip != null) {
            Assumptions.assumeTrue(false, skip);
        }
    }

    private static Outcome outcome() {
        Outcome current = outcome;
        if (current != null) {
            return current;
        }
        synchronized (LoopbackPort.class) {
            current = outcome;
            if (current == null) {
                current = probe();
                outcome = current;
            }
            return current;
        }
    }

    private static Outcome probe() {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            InetAddress loopback = InetAddress.getLoopbackAddress();
            server.bind(new InetSocketAddress(loopback, 0));
            try (Socket client = new Socket()) {
                client.connect(server.getLocalSocketAddress(), 2_000);
            }
            return Outcome.allowed();
        } catch (Exception e) {
            if (permissionDenied(e)) {
                return Outcome.denied();
            }
            return Outcome.failed(e);
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // 探完即关；关不掉不改已经探到的结果
                }
            }
        }
    }

    private static String portOneSkipReason() {
        String current = portOneSkip;
        if (current != null) {
            return current.isEmpty() ? null : current;
        }
        synchronized (LoopbackPort.class) {
            current = portOneSkip;
            if (current == null) {
                String found = probePortOne();
                portOneSkip = found == null ? "" : found;
                current = portOneSkip;
            }
            return current.isEmpty() ? null : current;
        }
    }

    /**
     * @return 被拒时为空；否则是跳过原因
     */
    private static String probePortOne() {
        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", 1), 1_000);
        } catch (ConnectException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("connection refused")) {
                return null;
            }
            return "连 127.0.0.1:1 不是连接被拒（" + e.getClass().getName() + "：" + e.getMessage() + "），跳过";
        } catch (Exception e) {
            return "连 127.0.0.1:1 失败（" + e.getClass().getName() + "：" + e.getMessage() + "），跳过";
        }
        return "127.0.0.1 的 1 号端口有人在听，跳过";
    }

    private static boolean permissionDenied(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard < 8) {
            guard++;
            if (current instanceof SecurityException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String text = message.toLowerCase(Locale.ROOT);
                if (text.contains("operation not permitted") || text.contains("permission denied")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable error) throws T {
        throw (T) error;
    }

    private static final class Outcome {
        private final boolean allowed;
        private final Throwable error;

        private Outcome(boolean allowed, Throwable error) {
            this.allowed = allowed;
            this.error = error;
        }

        private static Outcome allowed() {
            return new Outcome(true, null);
        }

        private static Outcome denied() {
            return new Outcome(false, null);
        }

        private static Outcome failed(Throwable error) {
            return new Outcome(false, error);
        }
    }
}
