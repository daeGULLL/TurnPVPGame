package com.turngame.server;

public class HttpRelayServerMain {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        int timeoutSec = args.length > 1 ? Integer.parseInt(args[1]) : 60;

        HttpRelayServer server = new HttpRelayServer(port, timeoutSec);
        server.start();
        
        // Graceful shutdown on JVM termination (Ctrl+C, System.exit(), etc.)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n=== Server Shutdown Initiated ===");
            server.stop();
        }, "ShutdownHook"));
        
        // Keep JVM running
        System.out.println("Server running. Press Ctrl+C to shutdown gracefully.");
        Thread.currentThread().join();
    }
}
