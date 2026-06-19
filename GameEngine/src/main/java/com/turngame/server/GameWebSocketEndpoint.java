package com.turngame.server;

import com.google.gson.Gson;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.command.AttackAction;
import com.turngame.engine.command.DefendAction;
import com.turngame.engine.command.EndTurnAction;
import com.turngame.engine.command.GameAction;
import com.turngame.engine.command.MoveAction;
import com.turngame.engine.command.UseSkillAction;
import com.turngame.server.protocol.RequestMessage;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WebSocket endpoint for real-time game events and actions.
 * Integrates with HttpRelayServer's existing player/match logic.
 */
@ServerEndpoint("/events")
public class GameWebSocketEndpoint {
    private static final Gson GSON = new Gson();
    private static HttpRelayServer relayServer;
    
    public static void setRelayServer(HttpRelayServer server) {
        relayServer = server;
    }

    private String playerId;
    private String matchId;
    private long lastEventSeq = 0;

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) throws IOException {
        String query = session.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq);
                    String value = pair.substring(eq + 1);
                    if ("playerId".equals(key)) {
                        playerId = value;
                    } else if ("after".equals(key)) {
                        try {
                            lastEventSeq = Long.parseLong(value);
                        } catch (NumberFormatException ignored) {
                            lastEventSeq = 0;
                        }
                    }
                }
            }
        }

        if (playerId == null || playerId.isBlank()) {
            session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "Missing playerId"));
            return;
        }

        if (relayServer == null) {
            session.close(new CloseReason(CloseCodes.TRY_AGAIN_LATER, "Server not initialized"));
            return;
        }

        relayServer.registerWebSocketSession(playerId, session);
        session.setMaxIdleTimeout(90_000); // 90 seconds
        System.out.println("WebSocket connected: " + playerId);

        // Replay queued events published before this websocket finished connecting.
        java.util.List<Map<String, Object>> replayEvents = relayServer.snapshotEventsAfter(playerId, lastEventSeq);
        System.out.println("[GameWebSocketEndpoint] replay events playerId=" + playerId + ", count=" + replayEvents.size() + ", after=" + lastEventSeq);
        for (Map<String, Object> eventEnvelope : replayEvents) {
            Object seqObj = eventEnvelope.get("seq");
            if (seqObj instanceof Number number) {
                lastEventSeq = number.longValue();
            }
            session.getBasicRemote().sendText(GSON.toJson(eventEnvelope));
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) throws IOException {
        if (relayServer == null || playerId == null) {
            return;
        }

        try {
            RequestMessage req = GSON.fromJson(message, RequestMessage.class);
            if (req == null) {
                return;
            }

            String type = req.type().toUpperCase(Locale.ROOT);
            Map<String, Object> payload = req.payload() == null ? Map.of() : req.payload();

            if ("ACTION".equalsIgnoreCase(type)) {
                handleAction(payload);
                sendAck(session, req.requestId());
            } else if ("PING".equalsIgnoreCase(type)) {
                sendPong(session, req.requestId());
            }
        } catch (Exception ex) {
            System.err.println("WebSocket message error: " + ex.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        if (relayServer != null && playerId != null) {
            relayServer.unregisterWebSocketSession(playerId);
            System.out.println("WebSocket closed: " + playerId + " - " + closeReason.getReasonPhrase());
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        System.err.println("WebSocket error for " + playerId + ": " + error.getMessage());
    }

    private void handleAction(Map<String, Object> payload) {
        String actionType = asString(payload.get("actionType"), "").toUpperCase(Locale.ROOT);
        if (actionType.isBlank()) {
            return;
        }

        matchId = asString(payload.get("matchId"), matchId);
        if (matchId == null || matchId.isBlank()) {
            return;
        }

        try {
            GameAction action = parseAction(actionType, payload);
            if (action != null) {
                relayServer.submitGameAction(matchId, playerId, action);
            }
        } catch (RuntimeException ex) {
            System.err.println("Action parse error: " + ex.getMessage());
        }
    }

    private GameAction parseAction(String actionType, Map<String, Object> payload) {
        ActionType type = ActionType.valueOf(actionType);
        if (type == ActionType.ATTACK) {
            String targetId = asString(payload.get("targetId"), "");
            int damage = asInt(payload.get("damage"), 20);
            return new AttackAction(playerId, targetId, damage);
        }
        if (type == ActionType.DEFEND) {
            String evadeSkillName = asString(payload.get("evadeSkillName"), "Dodge");
            long evadeStartTimeMs = asLong(payload.get("evadeStartTimeMs"), System.currentTimeMillis());
            return new DefendAction(playerId, evadeSkillName, evadeStartTimeMs);
        }
        if (type == ActionType.USE_SKILL) {
            String targetId = asString(payload.get("targetId"), "");
            String skillName = asString(payload.get("skillName"), "").trim();
            Integer targetCol = payload.get("targetCol") == null ? null : asInt(payload.get("targetCol"), 0);
            Integer targetRow = payload.get("targetRow") == null ? null : asInt(payload.get("targetRow"), 0);
            int damage = asInt(payload.get("damage"), 20);
            return new UseSkillAction(playerId, targetId, skillName, damage, targetCol, targetRow);
        }
        if (type == ActionType.MOVE) {
            int targetCol = asInt(payload.get("targetCol"), -1);
            int targetRow = asInt(payload.get("targetRow"), -1);
            long requestedAtMs = System.currentTimeMillis();
            return new MoveAction(playerId, targetCol, targetRow, requestedAtMs);
        }
        return new EndTurnAction(playerId);
    }

    private void sendAck(Session session, String requestId) throws IOException {
        Map<String, Object> ack = new HashMap<>();
        ack.put("type", "ACK");
        ack.put("requestId", requestId);
        session.getBasicRemote().sendText(GSON.toJson(ack));
    }

    private void sendPong(Session session, String requestId) throws IOException {
        Map<String, Object> pong = new HashMap<>();
        pong.put("type", "PONG");
        pong.put("requestId", requestId);
        session.getBasicRemote().sendText(GSON.toJson(pong));
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
