package com.turngame.client;

import com.google.gson.Gson;
import com.turngame.server.protocol.RequestMessage;
import com.turngame.server.protocol.ResponseMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class ConsoleClient {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        String nickname = args.length > 2 ? args[2] : "player";
        String characterType = args.length > 3 ? args[3] : "WARRIOR";

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {

            AtomicReference<String> myPlayerId = new AtomicReference<>();
            AtomicReference<String> matchId = new AtomicReference<>();

            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        ResponseMessage msg = GSON.fromJson(line, ResponseMessage.class);
                        if (msg == null || msg.payload() == null) {
                            System.out.println("[SERVER] " + line);
                            continue;
                        }

                        if ("JOINED".equalsIgnoreCase(msg.type())) {
                            myPlayerId.set(String.valueOf(msg.payload().get("playerId")));
                            System.out.println("Joined as " + myPlayerId.get());
                        }

                        if ("MATCH_STARTED".equalsIgnoreCase(msg.type())) {
                            matchId.set(String.valueOf(msg.payload().get("matchId")));
                            myPlayerId.set(String.valueOf(msg.payload().get("playerId")));
                            System.out.println("Match started: " + matchId.get() + " / you=" + myPlayerId.get());
                            System.out.println("Players: " + msg.payload().get("players"));
                            System.out.println("Map: " + msg.payload().get("map"));
                            System.out.println("Character: " + msg.payload().get("character"));
                            System.out.println("Skills: " + msg.payload().get("skills"));
                            continue;
                        }

                        if ("STATE_UPDATED".equalsIgnoreCase(msg.type())) {
                            renderState(msg.payload(), myPlayerId.get());
                            continue;
                        }

                        if ("ERROR".equalsIgnoreCase(msg.type())) {
                            System.out.println("ERROR: " + msg.payload());
                            continue;
                        }

                        if ("GAME_ENDED".equalsIgnoreCase(msg.type())) {
                            System.out.println("Game ended: " + msg.payload());
                            continue;
                        }

                        System.out.println("[SERVER] " + line);
                    }
                } catch (Exception ignored) {
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            send(out, new RequestMessage("JOIN", id(), Map.of("nickname", nickname, "characterType", characterType)));

            printHelp();
            String cmd;
            while ((cmd = console.readLine()) != null) {
                String[] parts = cmd.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isBlank()) {
                    continue;
                }

                if ("quit".equalsIgnoreCase(parts[0])) {
                    break;
                }

                String actorId = myPlayerId.get();
                String currentMatch = matchId.get();
                if (actorId == null || currentMatch == null) {
                    System.out.println("Not matched yet. Wait for MATCH_STARTED.");
                    continue;
                }

                if ("attack".equalsIgnoreCase(parts[0])) {
                    if (parts.length < 3) {
                        System.out.println("usage: attack <targetId> <damage>");
                        continue;
                    }
                    Map<String, Object> payload = basePayload(currentMatch, "ATTACK");
                    payload.put("targetId", parts[1]);
                    payload.put("damage", Integer.parseInt(parts[2]));
                    send(out, new RequestMessage("ACTION", id(), payload));
                    continue;
                }

                if ("skill".equalsIgnoreCase(parts[0])) {
                    if (parts.length < 3) {
                        System.out.println("usage: skill <targetId> <skillName>");
                        continue;
                    }
                    Map<String, Object> payload = basePayload(currentMatch, "USE_SKILL");
                    payload.put("targetId", parts[1]);
                    payload.put("skillName", String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)));
                    send(out, new RequestMessage("ACTION", id(), payload));
                    continue;
                }

                if ("defend".equalsIgnoreCase(parts[0])) {
                    Map<String, Object> payload = basePayload(currentMatch, "DEFEND");
                    send(out, new RequestMessage("ACTION", id(), payload));
                    continue;
                }

                if ("end".equalsIgnoreCase(parts[0])) {
                    Map<String, Object> payload = basePayload(currentMatch, "END_TURN");
                    send(out, new RequestMessage("ACTION", id(), payload));
                    continue;
                }

                if ("help".equalsIgnoreCase(parts[0])) {
                    printHelp();
                    continue;
                }

                System.out.println("Unknown command. Type 'help'.");
            }
        }
    }

    private static Map<String, Object> basePayload(String matchId, String actionType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("matchId", matchId);
        payload.put("actionType", actionType);
        return payload;
    }

    private static void send(PrintWriter out, RequestMessage msg) {
        out.println(GSON.toJson(msg));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void printHelp() {
        List<String> lines = List.of(
                "Commands:",
                "  attack <targetId> <damage>",
                "  skill <targetId> <skillName>",
                "  defend",
                "  end",
                "  help",
                "  quit"
        );
        lines.forEach(System.out::println);
    }

    @SuppressWarnings("unchecked")
    private static void renderState(Map<String, Object> payload, String myPlayerId) {
        String matchId = String.valueOf(payload.get("matchId"));
        String turnPlayerId = String.valueOf(payload.get("turnPlayerId"));
        System.out.println("\n=== STATE_UPDATED | match=" + matchId + " ===");
        System.out.println("Current turn: " + turnPlayerId + (turnPlayerId.equals(myPlayerId) ? " (YOUR TURN)" : ""));
        System.out.println("playerId\thp\tdefending\talive");

        Object playersObj = payload.get("players");
        if (playersObj instanceof List<?> players) {
            for (Object p : players) {
                if (p instanceof Map<?, ?> rawMap) {
                    Map<String, Object> row = (Map<String, Object>) rawMap;
                    System.out.println(
                            row.get("playerId") + "\t" +
                                    asInt(row.get("hp")) + "\t" +
                                    row.get("defending") + "\t" +
                                    row.get("alive"));
                }
            }
        }
        System.out.println("===================================\n");
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
