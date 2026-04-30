package com.magefight.ui;

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
import java.util.function.Consumer;

/**
 * 네트워크 기반 멀티플레이 클라이언트
 * MageFightFrame과 게임 서버를 연결합니다.
 */
public class GameNetworkClient {
    private static final Gson GSON = new Gson();

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String myPlayerId;
    private String matchId;
    private boolean connected;

    private Consumer<ResponseMessage> onMessageReceived;
    private Consumer<String> onErrorReceived;
    private Consumer<Void> onDisconnected;

    public GameNetworkClient() {
        this.connected = false;
    }

    /**
     * 서버에 연결합니다.
     */
    public void connect(String host, int port) throws Exception {
        this.socket = new Socket(host, port);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.connected = true;

        // 서버 메시지 수신 스레드
        Thread readerThread = new Thread(this::receiveLoop);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * 서버에서 메시지를 수신합니다.
     */
    private void receiveLoop() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                try {
                    ResponseMessage msg = GSON.fromJson(line, ResponseMessage.class);
                    if (msg != null) {
                        if ("ERROR".equalsIgnoreCase(msg.type())) {
                            if (onErrorReceived != null) {
                                onErrorReceived.accept(String.valueOf(msg.payload().get("message")));
                            }
                        } else {
                            if (onMessageReceived != null) {
                                onMessageReceived.accept(msg);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse server message: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Network error: " + e.getMessage());
        } finally {
            connected = false;
            if (onDisconnected != null) {
                onDisconnected.accept(null);
            }
        }
    }

    /**
     * 게임 참가 요청을 보냅니다.
     */
    public void joinGame(String nickname, String characterType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("nickname", nickname);
        payload.put("characterType", characterType);
        send(new RequestMessage("JOIN", genId(), payload));
    }

    /**
     * 게임 액션을 전송합니다.
     */
    public void sendAction(String actionType, Map<String, Object> actionDetails) {
        if (myPlayerId == null || matchId == null) {
            if (onErrorReceived != null) {
                onErrorReceived.accept("Not matched yet");
            }
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("matchId", matchId);
        payload.put("actionType", actionType);
        payload.putAll(actionDetails);
        send(new RequestMessage("ACTION", genId(), payload));
    }

    /**
     * 공격 액션을 전송합니다.
     */
    public void attack(String targetId, int damage) {
        Map<String, Object> details = new HashMap<>();
        details.put("targetId", targetId);
        details.put("damage", damage);
        sendAction("ATTACK", details);
    }

    /**
     * 방어 액션을 전송합니다.
     */
    public void defend(String evadeSkillName) {
        Map<String, Object> details = new HashMap<>();
        details.put("evadeSkillName", evadeSkillName);
        details.put("evadeStartTimeMs", System.currentTimeMillis());
        sendAction("DEFEND", details);
    }

    /**
     * 스킬 사용 액션을 전송합니다.
     */
    public void useSkill(String targetId, String skillName) {
        Map<String, Object> details = new HashMap<>();
        details.put("targetId", targetId);
        details.put("skillName", skillName);
        sendAction("USE_SKILL", details);
    }

    /**
     * 이동 액션을 전송합니다.
     */
    public void move(int targetCol, int targetRow) {
        Map<String, Object> details = new HashMap<>();
        details.put("targetCol", targetCol);
        details.put("targetRow", targetRow);
        sendAction("MOVE", details);
    }

    /**
     * 턴 종료 액션을 전송합니다.
     */
    public void endTurn() {
        sendAction("END_TURN", new HashMap<>());
    }

    /**
     * 메시지를 서버로 전송합니다.
     */
    private void send(RequestMessage msg) {
        if (out != null && connected) {
            out.println(GSON.toJson(msg));
        }
    }

    /**
     * 연결을 해제합니다.
     */
    public void disconnect() {
        connected = false;
        try {
            if (socket != null) socket.close();
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (Exception e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }

    // Getters
    public String getMyPlayerId() {
        return myPlayerId;
    }

    public void setMyPlayerId(String playerId) {
        this.myPlayerId = playerId;
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setOnMessageReceived(Consumer<ResponseMessage> consumer) {
        this.onMessageReceived = consumer;
    }

    public void setOnErrorReceived(Consumer<String> consumer) {
        this.onErrorReceived = consumer;
    }

    public void setOnDisconnected(Consumer<Void> consumer) {
        this.onDisconnected = consumer;
    }

    private static String genId() {
        return UUID.randomUUID().toString();
    }
}
