package com.turngame.server;

public class ServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        int timeoutSec = args.length > 1 ? Integer.parseInt(args[1]) : 20;

        GameServer server = new GameServer(port, timeoutSec);
        server.start();
    }
}
