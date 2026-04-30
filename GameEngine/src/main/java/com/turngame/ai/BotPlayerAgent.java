package com.turngame.ai;

import com.google.gson.Gson;
import com.turngame.server.protocol.RequestMessage;
import com.turngame.server.protocol.ResponseMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class BotPlayerAgent {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        String nickname = args.length > 2 ? args[2] : "bot";
        String characterType = args.length > 3 ? args[3] : "MAGE";

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            Random random = new Random();
            final String[] myPlayerId = {null};
            final String[] matchId = {null};
            final List<String> mySkills = new ArrayList<>();

            send(out, new RequestMessage("JOIN", id(), Map.of("nickname", nickname, "characterType", characterType)));

            String line;
            while ((line = in.readLine()) != null) {
                ResponseMessage msg = GSON.fromJson(line, ResponseMessage.class);
                if (msg == null || msg.payload() == null) {
                    continue;
                }

                if ("JOINED".equalsIgnoreCase(msg.type())) {
                    myPlayerId[0] = asString(msg.payload().get("playerId"));
                }

                if ("MATCH_STARTED".equalsIgnoreCase(msg.type())) {
                    matchId[0] = asString(msg.payload().get("matchId"));
                    myPlayerId[0] = asString(msg.payload().get("playerId"));
                    mySkills.clear();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> skills = (List<Map<String, Object>>) msg.payload().get("skills");
                    if (skills != null) {
                        for (Map<String, Object> skill : skills) {
                            mySkills.add(asString(skill.get("name")));
                        }
                    }
                }

                if ("STATE_UPDATED".equalsIgnoreCase(msg.type())) {
                    if (myPlayerId[0] == null || matchId[0] == null) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    List<String> readyPlayers = (List<String>) msg.payload().get("readyPlayers");
                    if (readyPlayers != null && readyPlayers.contains(myPlayerId[0])) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> players = (List<Map<String, Object>>) msg.payload().get("players");
                    String target = players.stream()
                            .map(p -> asString(p.get("playerId")))
                            .filter(pid -> !pid.equals(myPlayerId[0]))
                            .findFirst()
                            .orElse(null);
                    if (target == null) {
                        continue;
                    }

                    int roll = random.nextInt(100);
                    if (roll < 35 && !mySkills.isEmpty()) {
                        String skillName = mySkills.get(random.nextInt(mySkills.size()));
                        Map<String, Object> skillPayload = actionPayload(matchId[0], "USE_SKILL");
                        skillPayload.put("targetId", target);
                        skillPayload.put("skillName", skillName);
                        send(out, new RequestMessage("ACTION", id(), skillPayload));
                    } else if (roll < 60) {
                        Map<String, Object> defendPayload = actionPayload(matchId[0], "DEFEND");
                        send(out, new RequestMessage("ACTION", id(), defendPayload));
                    } else {
                        Map<String, Object> attackPayload = actionPayload(matchId[0], "ATTACK");
                        attackPayload.put("targetId", target);
                        attackPayload.put("damage", 20 + random.nextInt(11));
                        send(out, new RequestMessage("ACTION", id(), attackPayload));
                    }

                    Map<String, Object> endPayload = actionPayload(matchId[0], "END_TURN");
                    send(out, new RequestMessage("ACTION", id(), endPayload));
                }

                if ("GAME_ENDED".equalsIgnoreCase(msg.type())) {
                    break;
                }
            }
        }
    }

    private static Map<String, Object> actionPayload(String matchId, String actionType) {
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

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
