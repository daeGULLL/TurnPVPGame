package com.magefight.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.turngame.server.protocol.RequestMessage;
import com.turngame.server.protocol.ResponseMessage;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

/**
 * 네트워크 기반 멀티플레이 클라이언트
 * MageFightFrame과 게임 서버를 연결합니다. (HTTP REST API)
 */
public class GameNetworkClient {
    private static final Gson GSON = new Gson();

    public enum MatchState {
        IDLE,
        SEARCHING,
        MATCHED,
        DISCONNECTED
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();
    private volatile URI serverBaseUri;
    private String serverUrl;
    private int serverPort;
    private volatile String myPlayerId;
    private volatile String matchId;
    private volatile boolean connected;
    private Thread eventThread;
    private volatile Session wsSession;
    private volatile long lastEventSeq = 0;
    private volatile MatchState matchState = MatchState.IDLE;
    private volatile long matchSearchStartTimeMs = 0L;
    private volatile int connectionMode = 0; // 0=ws, 1=sse, 2=polling

    private volatile Consumer<ResponseMessage> onMessageReceived;
    private volatile Consumer<String> onErrorReceived;
    private volatile Consumer<Void> onDisconnected;
    private volatile Consumer<MatchState> onMatchStateChanged;
    private volatile ResponseMessage lastMatchedMessage;
    private volatile ResponseMessage lastMatchStartedMessage;
    private volatile ResponseMessage lastStateUpdatedMessage;

    public GameNetworkClient() {
        this.connected = false;
    }

    /**
     * 서버에 연결합니다 (HTTP REST API).
     */
    public void connect(String host, int port) throws Exception {
        String rawHost = host == null ? "" : host.trim();
        boolean explicitHttps = rawHost.startsWith("https://");
        boolean explicitHttp = rawHost.startsWith("http://");
        String normalizedHost = normalizeHost(rawHost);
        boolean useHttps = explicitHttps || (!explicitHttp && port == 443);

        this.serverUrl = (useHttps ? "https://" : "http://") + normalizedHost + ":" + port;
        this.serverBaseUri = URI.create(this.serverUrl);
        this.serverPort = port;

        try {
            verifyServerReachable();
        } catch (IllegalStateException ex) {
            if (shouldTryHttpsFallback(normalizedHost, port) && trySwitchToHttps(normalizedHost)) {
                System.out.println("Switched to HTTPS relay endpoint: " + serverUrl);
            } else {
                throw ex;
            }
        }

        this.connected = true;
        this.lastEventSeq = 0;

        // 이벤트 수신 스레드 시작 (SSE 우선, 실패 시 폴링 폴백)
        eventThread = new Thread(this::eventLoop, "GameNetworkClient-Events");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    private boolean shouldTryHttpsFallback(String normalizedHost, int port) {
        return port == 9090 && !isIpLiteral(normalizedHost);
    }

    private boolean trySwitchToHttps(String normalizedHost) {
        URI originalBase = serverBaseUri;
        String originalUrl = serverUrl;
        try {
            this.serverUrl = "https://" + normalizedHost;
            this.serverBaseUri = URI.create(this.serverUrl);
            verifyServerReachable();
            return true;
        } catch (Exception ex) {
            this.serverUrl = originalUrl;
            this.serverBaseUri = originalBase;
            return false;
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        // Treat bracketed IPv6 and dotted IPv4 as literal IP hosts.
        if ((host.startsWith("[") && host.endsWith("]")) || host.contains(":")) {
            return true;
        }
        return host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private void verifyServerReachable() throws Exception {
        URI healthUri = serverBaseUri.resolve("/health");
        HttpRequest healthRequest = HttpRequest.newBuilder()
            .uri(healthUri)
            .GET()
            .timeout(java.time.Duration.ofSeconds(5))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(healthRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Server health check failed: HTTP " + response.statusCode());
            }
        } catch (HttpConnectTimeoutException ex) {
            String suffix = serverPort == 9090
                    ? " If this domain is behind Cloudflare proxy, custom port 9090 may be blocked."
                    + " Use DNS-only for the game subdomain or expose a Cloudflare-supported port."
                    : " Verify tunnel ingress and DNS route for this host/port.";
            throw new IllegalStateException(
                "Connection timed out to " + serverUrl + "." + suffix,
                ex);
        }
    }

    private static String normalizeHost(String host) {
        String raw = host == null ? "" : host.trim();
        if (raw.startsWith("http://")) {
            raw = raw.substring("http://".length());
        } else if (raw.startsWith("https://")) {
            raw = raw.substring("https://".length());
        }
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            raw = raw.substring(0, slash);
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Server host is empty");
        }
        return raw;
    }

    private void eventLoop() {
        while (connected) {
            try {
                if (myPlayerId == null || myPlayerId.isBlank()) {
                    Thread.sleep(100);
                    continue;
                }

                if (connectionMode == 0) {
                    WsResult result = runWebSocket();
                    if (result == WsResult.UNSUPPORTED) {
                        connectionMode = 1;
                        System.out.println("WebSocket unavailable; falling back to SSE events.");
                    }
                    continue;
                }

                if (connectionMode == 1) {
                    SseResult result = runSseStream();
                    if (result == SseResult.UNSUPPORTED) {
                        connectionMode = 2;
                        System.out.println("SSE unavailable; falling back to long-polling events.");
                    }
                    continue;
                }

                pollEventsOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (HttpConnectTimeoutException e) {
                if (onErrorReceived != null) {
                    onErrorReceived.accept("Server connection timed out. Verify host/port and network route.");
                }
                break;
            } catch (Exception e) {
                System.err.println("Event loop exception: " + e.getMessage());
                if (connected) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        closeWebSocket();
        connected = false;
        setMatchState(MatchState.DISCONNECTED);
        if (onDisconnected != null) {
            onDisconnected.accept(null);
        }
    }

    private enum WsResult {
        COMPLETED,
        UNSUPPORTED
    }

    private enum SseResult {
        COMPLETED,
        UNSUPPORTED
    }

    private WsResult runWebSocket() throws Exception {
        boolean secure = serverUrl.startsWith("https://");
        String wsScheme = secure ? "wss://" : "ws://";
        String httpHost = serverBaseUri == null ? null : serverBaseUri.getHost();
        String wsHost = resolveWebSocketHost(httpHost);
        int wsPort = resolveWebSocketPort(httpHost, wsHost);

        String wsUrl = wsScheme + wsHost;
        if (wsPort > 0) {
            wsUrl += ":" + wsPort;
        }
        wsUrl += "/events?playerId="
                + URLEncoder.encode(myPlayerId, StandardCharsets.UTF_8)
                + "&after=" + lastEventSeq;

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        
        try {
            Session session = container.connectToServer(
                new GameWebSocketClient(),
                ClientEndpointConfig.Builder.create().build(),
                new URI(wsUrl)
            );
            wsSession = session;
            System.out.println("WebSocket connected: " + wsUrl);

            // Keep a single websocket session alive; reconnect only after it is closed.
            while (connected && session.isOpen()) {
                Thread.sleep(250);
            }
            return WsResult.COMPLETED;
        } catch (DeploymentException ex) {
            if (ex.getCause() instanceof java.net.ConnectException) {
                System.err.println("WebSocket connection refused (" + wsUrl + "), trying SSE");
                return WsResult.UNSUPPORTED;
            }
            throw ex;
        } finally {
            if (wsSession != null && !wsSession.isOpen()) {
                wsSession = null;
            }
        }
    }

    private String resolveWebSocketHost(String httpHost) {
        String configured = readConfig("magefight.ws.host", "MAGEFIGHT_WS_HOST");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        if (httpHost == null || httpHost.isBlank()) {
            return "localhost";
        }
        if (isIpLiteral(httpHost) || "localhost".equalsIgnoreCase(httpHost)) {
            return httpHost;
        }
        if (httpHost.startsWith("ws-")) {
            return httpHost;
        }
        return "ws-" + httpHost;
    }

    private int resolveWebSocketPort(String httpHost, String wsHost) {
        Integer configuredPort = readPortConfig("magefight.ws.port", "MAGEFIGHT_WS_PORT");
        if (configuredPort != null) {
            return configuredPort;
        }

        // For internet domains behind Cloudflare, prefer default 443/80 with no explicit port.
        if (wsHost != null && httpHost != null && !wsHost.equalsIgnoreCase(httpHost) && !isIpLiteral(httpHost)) {
            return -1;
        }
        return serverPort + 1;
    }

    private String readConfig(String propertyKey, String envKey) {
        String value = System.getProperty(propertyKey);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }

    private Integer readPortConfig(String propertyKey, String envKey) {
        String value = readConfig(propertyKey, envKey);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 1 && parsed <= 65535) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // ignore invalid overrides
        }
        return null;
    }

    private void closeWebSocket() {
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.close();
            } catch (Exception ignored) {
            }
        }
    }

    private class GameWebSocketClient extends Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(new MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    try {
                        processEventEnvelopeJson(message);
                    } catch (Exception ex) {
                        System.err.println("WebSocket message parse error: " + ex.getMessage());
                    }
                }
            });
        }
    }

    private SseResult runSseStream() throws Exception {
        String streamUrl = serverUrl + "/api/events/stream?playerId="
                + URLEncoder.encode(myPlayerId, StandardCharsets.UTF_8)
                + "&after=" + lastEventSeq;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(streamUrl))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status == 404) {
            Thread.sleep(100);
            return SseResult.COMPLETED;
        }
        if (status == 405 || status == 501 || status == 426) {
            return SseResult.UNSUPPORTED;
        }
        if (status != 200) {
            System.err.println("SSE stream error: " + status);
            Thread.sleep(1000);
            return SseResult.COMPLETED;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            String data = null;
            while (connected && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (data != null && !data.isBlank()) {
                        processEventEnvelopeJson(data);
                    }
                    data = null;
                    continue;
                }
                if (line.startsWith("data:")) {
                    data = line.substring(5).trim();
                }
            }
        }

        return SseResult.COMPLETED;
    }

    @SuppressWarnings("unchecked")
    private void pollEventsOnce() throws Exception {
        String eventsUrl = serverUrl + "/api/events?playerId="
                + URLEncoder.encode(myPlayerId, StandardCharsets.UTF_8)
                + "&after=" + lastEventSeq;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(eventsUrl))
                .GET()
                .timeout(java.time.Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 200) {
            Map<String, Object> responseBody = GSON.fromJson(response.body(), Map.class);
            List<Map<String, Object>> events = (List<Map<String, Object>>) responseBody.get("events");
            if (events != null) {
                for (Map<String, Object> eventEnvelope : events) {
                    processEventEnvelope(eventEnvelope);
                }
            }
            return;
        }
        if (response.statusCode() == 404) {
            Thread.sleep(100);
            return;
        }

        System.err.println("Event poll error: " + response.statusCode());
        Thread.sleep(1000);
    }

    @SuppressWarnings("unchecked")
    private void processEventEnvelopeJson(String data) {
        Map<String, Object> eventEnvelope = GSON.fromJson(data, Map.class);
        if (eventEnvelope == null) {
            return;
        }
        processEventEnvelope(eventEnvelope);
    }

    @SuppressWarnings("unchecked")
    private void processEventEnvelope(Map<String, Object> eventEnvelope) {
        Number seqNumber = (Number) eventEnvelope.get("seq");
        if (seqNumber == null) {
            return;
        }
        long seq = seqNumber.longValue();
        if (seq <= lastEventSeq) {
            return;
        }
        String type = (String) eventEnvelope.get("type");
        String requestId = (String) eventEnvelope.get("requestId");
        Map<String, Object> payload = (Map<String, Object>) eventEnvelope.get("payload");

        System.out.println("[GameNetworkClient] event type=" + type + ", seq=" + seq + ", requestId=" + requestId);

        lastEventSeq = seq;
        ResponseMessage msg = new ResponseMessage(type, requestId, payload != null ? payload : Map.of());

        if ("ERROR".equalsIgnoreCase(type)) {
            setMatchState(MatchState.IDLE);
            if (onErrorReceived != null) {
                onErrorReceived.accept(String.valueOf(msg.payload().getOrDefault("message", "Unknown error")));
            }
            return;
        }

        String payloadMatchId = asNonBlankString(msg.payload().get("matchId"));
        if (payloadMatchId != null) {
            this.matchId = payloadMatchId;
        }
        String payloadPlayerId = asNonBlankString(msg.payload().get("playerId"));
        if (payloadPlayerId != null) {
            this.myPlayerId = payloadPlayerId;
        }

        if ("MATCHED".equalsIgnoreCase(type)) {
            setMatchState(MatchState.MATCHED);
            lastMatchedMessage = msg;
        } else if ("MATCH_STARTED".equalsIgnoreCase(type)) {
            lastMatchStartedMessage = msg;
        } else if ("STATE_UPDATED".equalsIgnoreCase(type)) {
            lastStateUpdatedMessage = msg;
        }
        if (onMessageReceived != null) {
            onMessageReceived.accept(msg);
        }
    }

    /**
     * 게임 찾기를 시작합니다 (매칭 대기).
     */
    public void findGame(String nickname, String characterType) {
        findGame(nickname, characterType, null, null);
    }

    public void findGame(String nickname, String characterType, String accountId, String characterDisplayName) {
        findGame(nickname, characterType, accountId, characterDisplayName, null, null);
    }

    public void findGame(
            String nickname,
            String characterType,
            String accountId,
            String characterDisplayName,
            List<Map<String, Object>> skills,
            Integer turnEnergyCap
    ) {
        if (matchState == MatchState.SEARCHING) {
            System.out.println("Already searching for a game");
            return;
        }
        lastMatchedMessage = null;
        lastMatchStartedMessage = null;
        lastStateUpdatedMessage = null;
        setMatchState(MatchState.SEARCHING);
        this.matchSearchStartTimeMs = System.currentTimeMillis();
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("nickname", nickname);
        payload.put("characterType", characterType);
        if (accountId != null && !accountId.isBlank()) {
            payload.put("accountId", accountId);
        }
        if (characterDisplayName != null && !characterDisplayName.isBlank()) {
            payload.put("characterDisplayName", characterDisplayName);
        }
        if (skills != null && !skills.isEmpty()) {
            payload.put("skills", skills);
        }
        if (turnEnergyCap != null && turnEnergyCap > 0) {
            payload.put("turnEnergyCap", turnEnergyCap);
        }
        
        new Thread(() -> {
            try {
                Map<String, Object> requestPayload = new HashMap<>(payload);
                RequestMessage req = new RequestMessage("FIND_GAME", UUID.randomUUID().toString(), requestPayload);
                
                String jsonBody = GSON.toJson(req);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(serverUrl + "/api/join"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                
                if (response.statusCode() == 200) {
                    ResponseMessage resp = GSON.fromJson(response.body(), ResponseMessage.class);
                    if (resp != null && resp.payload() != null) {
                        Object playerIdObj = resp.payload().get("playerId");
                        if (playerIdObj != null) {
                            myPlayerId = String.valueOf(playerIdObj);
                            System.out.println("Joined game as " + myPlayerId);

                            // Join and match creation can race with websocket setup; fetch once immediately.
                            new Thread(() -> {
                                try {
                                    pollEventsOnce();
                                } catch (Exception ex) {
                                    System.err.println("Initial event sync error: " + ex.getMessage());
                                }
                            }, "GameNetworkClient-InitialEventSync").start();
                        }
                    }
                } else {
                    ResponseMessage errorResp = GSON.fromJson(response.body(), ResponseMessage.class);
                    String errorMsg = errorResp != null && errorResp.payload() != null 
                        ? (String) errorResp.payload().get("message") 
                        : "Join failed with status " + response.statusCode();
                    setMatchState(MatchState.IDLE);
                    if (onErrorReceived != null) {
                        onErrorReceived.accept(errorMsg);
                    }
                }
            } catch (HttpConnectTimeoutException e) {
                setMatchState(MatchState.IDLE);
                if (onErrorReceived != null) {
                    onErrorReceived.accept("Join request timed out. Check server route; Cloudflare proxy can block port 9090.");
                }
            } catch (Exception e) {
                System.err.println("Find game error: " + e.getMessage());
                setMatchState(MatchState.IDLE);
                if (onErrorReceived != null) {
                    onErrorReceived.accept(e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 게임 찾기를 취소합니다.
     */
    public void cancelMatchmaking() {
        if (matchState != MatchState.SEARCHING) {
            return;
        }
        setMatchState(MatchState.IDLE);
    }

    /**
     * 게임 참가 요청을 보냅니다.
     */
    public void joinGame(String nickname, String characterType) {
        findGame(nickname, characterType, null, null);
    }

    public void joinGame(String nickname, String characterType, String accountId, String characterDisplayName) {
        findGame(nickname, characterType, accountId, characterDisplayName, null, null);
    }

    /**
     * 게임 액션을 전송합니다.
     */
    public void sendAction(String actionType, Map<String, Object> actionDetails) {
        hydrateIdentityFromCachedMessages();
        if (myPlayerId == null || myPlayerId.isBlank()) {
            if (onErrorReceived != null) {
                onErrorReceived.accept("Not matched yet");
            }
            return;
        }

        String effectiveMatchId = resolveCurrentMatchId();
        if (effectiveMatchId == null || effectiveMatchId.isBlank()) {
            if (onErrorReceived != null) {
                onErrorReceived.accept("Not matched yet");
            }
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", myPlayerId);
        payload.put("matchId", effectiveMatchId);
        payload.put("actionType", actionType);
        payload.putAll(actionDetails);

        System.out.println("[GameNetworkClient] sendAction type=" + actionType
            + ", playerId=" + myPlayerId + ", matchId=" + effectiveMatchId);

        sendActionViaHttp(payload);
    }

    private void sendActionViaHttp(Map<String, Object> payload) {
        new Thread(() -> {
            try {
                RequestMessage req = new RequestMessage("ACTION", UUID.randomUUID().toString(), payload);
                String jsonBody = GSON.toJson(req);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(serverUrl + "/api/action"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    ResponseMessage errorResp = GSON.fromJson(response.body(), ResponseMessage.class);
                    String errorMsg = errorResp != null && errorResp.payload() != null
                        ? (String) errorResp.payload().get("message")
                        : "Action failed";
                    if (onErrorReceived != null) {
                        onErrorReceived.accept(errorMsg);
                    }
                    return;
                }

                requestImmediateEventSync();
            } catch (Exception e) {
                System.err.println("Action error: " + e.getMessage());
                if (onErrorReceived != null) {
                    onErrorReceived.accept(e.getMessage());
                }
            }
        }).start();
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
        useSkill(targetId, skillName, null, null);
    }

    /**
     * 조준 좌표를 포함한 스킬 사용 액션을 전송합니다.
     */
    public void useSkill(String targetId, String skillName, Integer targetCol, Integer targetRow) {
        Map<String, Object> details = new HashMap<>();
        details.put("targetId", targetId);
        details.put("skillName", skillName);
        if (targetCol != null && targetRow != null) {
            details.put("targetCol", targetCol);
            details.put("targetRow", targetRow);
        }
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
     * 연결을 해제합니다.
     */
    public void disconnect() {
        connected = false;
        closeWebSocket();
        
        if (myPlayerId != null) {
            new Thread(() -> {
                try {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("playerId", myPlayerId);
                    RequestMessage req = new RequestMessage("DISCONNECT", UUID.randomUUID().toString(), payload);
                    String jsonBody = GSON.toJson(req);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(new URI(serverUrl + "/api/disconnect"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                            .timeout(java.time.Duration.ofSeconds(5))
                            .build();

                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    System.err.println("Disconnect error: " + e.getMessage());
                }
            }).start();
        }
        
        if (eventThread != null && eventThread.isAlive()) {
            try {
                eventThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        setMatchState(MatchState.DISCONNECTED);
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

    public MatchState getMatchState() {
        return matchState;
    }

    private void setMatchState(MatchState newState) {
        if (this.matchState != newState) {
            System.out.println("[GameNetworkClient] matchState " + this.matchState + " -> " + newState);
            this.matchState = newState;
            if (onMatchStateChanged != null) {
                onMatchStateChanged.accept(newState);
            }
        }
    }

    public long getMatchSearchElapsedMs() {
        if (matchSearchStartTimeMs <= 0) {
            return 0;
        }
        return System.currentTimeMillis() - matchSearchStartTimeMs;
    }

    public void setOnMessageReceived(Consumer<ResponseMessage> consumer) {
        this.onMessageReceived = consumer;
        replayCachedMessagesTo(consumer);
    }

    private void replayCachedMessagesTo(Consumer<ResponseMessage> consumer) {
        if (consumer == null) {
            return;
        }
        ResponseMessage matched = lastMatchedMessage;
        if (matched != null) {
            consumer.accept(matched);
        }
        ResponseMessage started = lastMatchStartedMessage;
        if (started != null) {
            consumer.accept(started);
        }
        ResponseMessage stateUpdated = lastStateUpdatedMessage;
        if (stateUpdated != null) {
            consumer.accept(stateUpdated);
        }
    }

    public void setOnErrorReceived(Consumer<String> consumer) {
        this.onErrorReceived = consumer;
    }

    public void setOnDisconnected(Consumer<Void> consumer) {
        this.onDisconnected = consumer;
    }

    public void setOnMatchStateChanged(Consumer<MatchState> consumer) {
        this.onMatchStateChanged = consumer;
    }

    public void requestImmediateEventSync() {
        if (!connected || myPlayerId == null || myPlayerId.isBlank()) {
            return;
        }
        new Thread(() -> {
            try {
                pollEventsOnce();
            } catch (Exception ex) {
                System.err.println("Manual event sync error: " + ex.getMessage());
            }
        }, "GameNetworkClient-ManualEventSync").start();
    }

    private void hydrateIdentityFromCachedMessages() {
        if (myPlayerId != null && !myPlayerId.isBlank() && matchId != null && !matchId.isBlank()) {
            return;
        }
        if (myPlayerId == null || myPlayerId.isBlank()) {
            String cachedPlayerId = extractPayloadString(lastMatchStartedMessage, "playerId");
            if (cachedPlayerId == null) {
                cachedPlayerId = extractPayloadString(lastMatchedMessage, "playerId");
            }
            if (cachedPlayerId != null) {
                myPlayerId = cachedPlayerId;
            }
        }
        if (matchId == null || matchId.isBlank()) {
            String cachedMatchId = resolveCurrentMatchId();
            if (cachedMatchId != null) {
                matchId = cachedMatchId;
            }
        }
    }

    private String resolveCurrentMatchId() {
        if (matchId != null && !matchId.isBlank()) {
            return matchId;
        }
        String fromState = extractPayloadString(lastStateUpdatedMessage, "matchId");
        if (fromState != null) {
            return fromState;
        }
        String fromStart = extractPayloadString(lastMatchStartedMessage, "matchId");
        if (fromStart != null) {
            return fromStart;
        }
        return extractPayloadString(lastMatchedMessage, "matchId");
    }

    private String extractPayloadString(ResponseMessage msg, String key) {
        if (msg == null || msg.payload() == null || key == null || key.isBlank()) {
            return null;
        }
        return asNonBlankString(msg.payload().get(key));
    }

    private String asNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }
}
