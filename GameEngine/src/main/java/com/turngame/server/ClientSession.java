package com.turngame.server;

import java.io.PrintWriter;
import java.net.Socket;

public record ClientSession(String playerId, String nickname, Socket socket, PrintWriter out) {
}
