package org.frostnova.nova.adapter.qqbot;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletionStage;

interface QqBotWebSocketTransport {
    Connection connect(URI uri, Listener listener) throws IOException, InterruptedException;

    interface Connection {
        CompletionStage<?> send(String text);

        void close();
    }

    interface Listener {
        void onText(String text);

        void onClose(int code);

        void onFailure();
    }
}
