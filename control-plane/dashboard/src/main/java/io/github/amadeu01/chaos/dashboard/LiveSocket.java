package io.github.amadeu01.chaos.dashboard;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;

/** Browsers connect here; LivePublisher pushes to every open connection. */
@WebSocket(path = "/live")
public class LiveSocket {

    @OnOpen
    void open() {
    }
}
