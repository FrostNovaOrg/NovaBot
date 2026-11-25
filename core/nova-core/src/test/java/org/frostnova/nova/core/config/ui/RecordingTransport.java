package org.frostnova.nova.core.config.ui;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 只记不发的发信通道：记下连到哪台服务器、用哪个账号口令登录、发了哪封信
 * <p>
 * 由测试资源 {@code META-INF/services/jakarta.mail.Provider} 登记成协议 {@link #PROTOCOL}。
 * 本机开不了端口，起不了一台假的邮件服务器；换成在发信库这一层截下来，
 * 发信器往哪儿连、拿什么登录照样看得见。
 */
public class RecordingTransport extends Transport {
    public static final String PROTOCOL = "nova-recording";

    /**
     * 一次登录
     */
    public record Connection(String host, int port, String user, String password) {
    }

    /**
     * 发出的一封信
     */
    public record Sent(Connection via, String from, String subject) {
    }

    /**
     * 发信库按服务发现找到这一项，把协议 {@link #PROTOCOL} 交给本类
     */
    public static final class Registration extends Provider {
        public Registration() {
            super(Type.TRANSPORT, PROTOCOL, RecordingTransport.class.getName(), "NovaBot tests", null);
        }
    }

    static final List<Connection> CONNECTIONS = new CopyOnWriteArrayList<>();

    static final List<Sent> SENT = new CopyOnWriteArrayList<>();

    private volatile Connection current;

    public RecordingTransport(Session session, URLName url) {
        super(session, url);
    }

    static void reset() {
        CONNECTIONS.clear();
        SENT.clear();
    }

    @Override
    protected boolean protocolConnect(String host, int port, String user, String password) {
        current = new Connection(host, port, user, password);
        CONNECTIONS.add(current);
        return true;
    }

    @Override
    public void sendMessage(Message message, Address[] addresses) throws MessagingException {
        SENT.add(new Sent(current, InternetAddress.toString(message.getFrom()), message.getSubject()));
    }
}
