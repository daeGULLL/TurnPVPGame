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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final Object eventSeqLock = new Object();
    private volatile MatchState matchState = MatchState.IDLE;
    private volatile long matchSearchStartTimeMs = 0L;
    // 0=ws, 1=sse, 2=polling.
    // long-poll(2)을 기본으로 둔다: WS는 서버 push가 한 번 유실되면(전송 오류 시 세션
    // unregister) 가만히 있는 쪽이 영영 이벤트를 못 받는 구조라, surrender/정산처럼
    // 본인이 행동하지 않을 때 도착하는 이벤트가 통째로 누락된다. long-poll은 서버가
    // publish 시 notifyAll로 대기 중인 요청을 즉시 깨우므로 지연도 거의 없고,
    // 무엇보다 "가만히 있어도" 확실히 수신된다. (명시적 pollEventsOnce가 이미
    // 안정적으로 동작함이 확인됨.)
    private volatile int connectionMode = 2;

    private volatile Consumer<ResponseMessage> onMessageReceived;
    private volatile Consumer<String> onErrorReceived;
    private volatile Consumer<Void> onDisconnected;
    private volatile Consumer<MatchState> onMatchStateChanged;
    private volatile Consumer<String> onLobbyNotice;
    private volatile String skinColorHex;
    private volatile String outfitColorHex;
    private volatile ResponseMessage lastMatchedMessage;
    private volatile ResponseMessage lastMatchStartedMessage;
    private volatile ResponseMessage lastStateUpdatedMessage;
    private volatile boolean resumableMatchAvailable;
    private volatile String resumableMatchId;
    private volatile long resumableReconnectDeadlineEpochMs;
    private final ExecutorService actionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "GameNetworkClient-Actions");
        thread.setDaemon(true);
        return thread;
    });

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
        // 새 연결을 시작할 때 이전 게임의 식별자/캐시를 비운다.
        // 그러지 않으면 eventLoop가 옛 playerId로 after=0 폴링을 해서
        // 서버에 남아있는 지난 게임 이벤트 큐를 통째로 다시 받아 재생한다.
        // (myPlayerId는 곧이어 새 findGame의 JOINED 응답이 채운다. eventLoop는
        //  myPlayerId가 null인 동안 폴링하지 않고 대기한다.)
        this.myPlayerId = null;
        this.matchId = null;
        this.lastMatchedMessage = null;
        this.lastMatchStartedMessage = null;
        this.lastStateUpdatedMessage = null;

        // 이벤트 수신 스레드 시작 (WS 우선, 실패 시 SSE/폴링 폴백)
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

            // WebSocket이 열려 있어도 서버 푸시가 유실될 수 있다(예: 일시적 send 오류로
            // 서버가 세션을 unregister하면 이후 푸시가 사라지는데, 클라이언트는 끊긴 걸
            // 모른 채 재연결도 하지 않는다). 특히 상대가 가만히 있을 때 surrender/GAME_ENDED
            // 같은 단발 이벤트가 통째로 유실된다. 그래서 WS 모드에서도 이벤트 큐 long-poll을
            // 안전망으로 함께 돌린다. seq 기반 dedup(processEventEnvelope)이 중복 처리를 막는다.
            while (connected && session.isOpen()) {
                try {
                    pollEventsOnce();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception pollEx) {
                    // 안전망 폴링 실패는 WS 세션을 끊지 않고 잠깐 쉬었다 재시도한다.
                    System.err.println("WS-mode safety poll error: " + pollEx.getMessage());
                    Thread.sleep(1000);
                }
            }
            return WsResult.COMPLETED;
        } catch (DeploymentException ex) {
            // 어떤 이유로든 WS 연결/업그레이드가 실패하면 SSE/폴링으로 폴백한다.
            // 예외를 re-throw하면 eventLoop가 WS만 무한 재시도하며 passive 수신이 멈춘다
            // (가만히 있는 쪽이 surrender/정산 이벤트를 영영 못 받게 되는 원인).
            System.err.println("WebSocket connect failed (" + wsUrl + "): " + ex.getMessage() + " — falling back to SSE/poll");
            return WsResult.UNSUPPORTED;
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
        // WS 푸시와 안전망 long-poll 등 서로 다른 전송 경로가 같은 이벤트를 동시에 처리할 수
        // 있으므로, seq dedup과 핸들러 디스패치를 한 번에 직렬화한다. 이렇게 하면 핸들러는
        // 기존처럼 한 번에 하나씩(단일 스레드처럼) 호출된다.
        synchronized (eventSeqLock) {
            if (seq <= lastEventSeq) {
                return;
            }
            lastEventSeq = seq;

            String type = (String) eventEnvelope.get("type");
            String requestId = (String) eventEnvelope.get("requestId");
            Map<String, Object> payload = (Map<String, Object>) eventEnvelope.get("payload");

            System.out.println("[GameNetworkClient] event type=" + type + ", seq=" + seq + ", requestId=" + requestId);

            Map<String, Object> normalizedPayload = payload == null ? new HashMap<>() : new HashMap<>(payload);
            normalizedPayload.put("_eventSeq", seq);
            normalizedPayload.put("_transport", currentTransportName());
            ResponseMessage msg = new ResponseMessage(type, requestId, normalizedPayload);

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
        this.resumableMatchAvailable = false;
        this.resumableMatchId = null;
        this.resumableReconnectDeadlineEpochMs = 0L;
        
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
        if (skinColorHex != null && !skinColorHex.isBlank()) {
            payload.put("skinColorHex", skinColorHex);
        }
        if (outfitColorHex != null && !outfitColorHex.isBlank()) {
            payload.put("outfitColorHex", outfitColorHex);
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

                            // In fallback modes, trigger one immediate pull.
                            if (!isActiveWebSocket()) {
                                requestImmediateEventSync();
                            }
                        }

                        Object lastResultObj = resp.payload().get("lastGameResult");
                        if (lastResultObj instanceof Map<?, ?> lastResult) {
                            boolean won = Boolean.TRUE.equals(lastResult.get("won"))
                                    || "true".equalsIgnoreCase(String.valueOf(lastResult.get("won")));
                            String reason = String.valueOf(lastResult.get("reason"));
                            String notice = formatLastGameResult(reason, won);
                            Consumer<String> noticeConsumer = onLobbyNotice;
                            if (noticeConsumer != null) {
                                noticeConsumer.accept(notice);
                            }
                        }

                        Object resumableObj = resp.payload().get("canResume");
                        boolean canResume = Boolean.TRUE.equals(resumableObj)
                                || "true".equalsIgnoreCase(String.valueOf(resumableObj));
                        if (canResume) {
                            String resumableId = asNonBlankString(resp.payload().get("resumableMatchId"));
                            if (resumableId != null) {
                                this.resumableMatchAvailable = true;
                                this.resumableMatchId = resumableId;
                                this.matchId = resumableId;
                                this.resumableReconnectDeadlineEpochMs = asLong(resp.payload().get("reconnectDeadlineEpochMs"), 0L);
                                setMatchState(MatchState.IDLE);
                            }
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
        if (!connected) {
            if (onErrorReceived != null) {
                onErrorReceived.accept("Not connected");
            }
            return;
        }

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

        enqueueAction(payload);
    }

    private void enqueueAction(Map<String, Object> payload) {
        actionExecutor.submit(() -> {
            if (!connected) {
                return;
            }
            // Keep WebSocket for events, but send actions via HTTP for deterministic server ack/error handling.
            sendActionViaHttp(payload);
        });
    }

    private void sendActionViaHttp(Map<String, Object> payload) {
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
                System.err.println("[GameNetworkClient] HTTP action failed status=" + response.statusCode() + ", body=" + response.body());
                ResponseMessage errorResp = GSON.fromJson(response.body(), ResponseMessage.class);
                String errorMsg = "Action failed";
                if (errorResp != null && errorResp.payload() != null) {
                    errorMsg = String.valueOf(errorResp.payload().getOrDefault("message", errorMsg));
                }
                if (onErrorReceived != null) {
                    onErrorReceived.accept(errorMsg);
                }
                return;
            }

            String actionType = String.valueOf(payload.getOrDefault("actionType", ""));
            if ("SURRENDER".equalsIgnoreCase(actionType)) {
                requestImmediateEventSync();
                return;
            }

            if (!isActiveWebSocket()) {
                requestImmediateEventSync();
            }
        } catch (Exception e) {
            System.err.println("Action error: " + e.getMessage());
            if (onErrorReceived != null) {
                onErrorReceived.accept(e.getMessage());
            }
        }
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
     * 기권 액션을 전송합니다.
     */
    public void surrender() {
        sendAction("SURRENDER", new HashMap<>());
    }

    /**
     * 연결을 해제합니다.
     */
    public void disconnect() {
        connected = false;
        clearResumableMatch();
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

    public boolean hasResumableMatch() {
        return resumableMatchAvailable && resumableMatchId != null && !resumableMatchId.isBlank();
    }

    public String getResumableMatchId() {
        return resumableMatchId;
    }

    public long getResumableReconnectDeadlineEpochMs() {
        return resumableReconnectDeadlineEpochMs;
    }

    public void clearResumableMatch() {
        resumableMatchAvailable = false;
        resumableMatchId = null;
        resumableReconnectDeadlineEpochMs = 0L;
    }

    public void resumeMatch() {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }
        if (myPlayerId == null || myPlayerId.isBlank()) {
            throw new IllegalStateException("Missing playerId");
        }
        String targetMatchId = resumableMatchId;
        if (targetMatchId == null || targetMatchId.isBlank()) {
            targetMatchId = matchId;
        }
        if (targetMatchId == null || targetMatchId.isBlank()) {
            throw new IllegalStateException("Missing resumable matchId");
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("playerId", myPlayerId);
            payload.put("matchId", targetMatchId);
            RequestMessage req = new RequestMessage("RESUME", UUID.randomUUID().toString(), payload);
            String jsonBody = GSON.toJson(req);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(serverUrl + "/api/resume"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                String message = "Resume failed";
                try {
                    ResponseMessage errorResp = GSON.fromJson(response.body(), ResponseMessage.class);
                    if (errorResp != null && errorResp.payload() != null) {
                        message = String.valueOf(errorResp.payload().getOrDefault("message", message));
                    }
                } catch (Exception ignored) {
                    // use default message
                }
                throw new IllegalStateException(message);
            }
            this.matchId = targetMatchId;
            clearResumableMatch();
            requestImmediateEventSync();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Resume failed: " + ex.getMessage(), ex);
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

    /**
     * 로비에서 보여줄 안내 메시지(예: 오프라인 중 종료된 이전 대전 결과) 콜백을 등록합니다.
     */
    public void setOnLobbyNotice(Consumer<String> consumer) {
        this.onLobbyNotice = consumer;
    }

    /**
     * 매칭 시 서버로 보낼 내 캐릭터 외형(피부/옷 색)을 설정합니다. findGame 호출 전에 지정하세요.
     */
    public void setAppearance(String skinColorHex, String outfitColorHex) {
        this.skinColorHex = skinColorHex;
        this.outfitColorHex = outfitColorHex;
    }

    private String formatLastGameResult(String reason, boolean won) {
        if ("PLAYER_SURRENDERED".equalsIgnoreCase(reason)) {
            return won ? "이전 대전: 상대가 기권하여 승리 처리되었습니다." : "이전 대전: 기권으로 패배 처리되었습니다.";
        }
        if ("PLAYER_ABANDONED".equalsIgnoreCase(reason)) {
            return won ? "이전 대전: 상대 미복귀로 승리 처리되었습니다." : "이전 대전: 미복귀로 패배 처리되었습니다.";
        }
        return won ? "이전 대전에서 승리했습니다." : "이전 대전이 종료되었습니다.";
    }

    public void requestImmediateEventSync() {
        if (!connected || myPlayerId == null || myPlayerId.isBlank()) {
            return;
        }
        if (isActiveWebSocket()) {
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

    private boolean isActiveWebSocket() {
        Session currentWs = wsSession;
        return connectionMode == 0 && currentWs != null && currentWs.isOpen();
    }

    private String currentTransportName() {
        if (connectionMode == 0) {
            return "ws";
        }
        if (connectionMode == 1) {
            return "sse";
        }
        return "poll";
    }

    private long asLong(Object value, long defaultValue) {
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
