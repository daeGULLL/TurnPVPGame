package com.turngame.server;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.GameSession;
import com.turngame.engine.TurnManager;
import com.turngame.engine.command.AttackAction;
import com.turngame.engine.command.DefendAction;
import com.turngame.engine.command.EndTurnAction;
import com.turngame.engine.command.GameAction;
import com.turngame.engine.command.MoveAction;
import com.turngame.engine.command.UseSkillAction;
import com.turngame.engine.rules.BasicRuleSet;
import com.turngame.event.ActionAppliedEvent;
import com.turngame.event.EventBus;
import com.turngame.event.GameEndedEvent;
import com.turngame.event.TurnStartedEvent;
import com.turngame.factory.character.CharacterFactoryProvider;
import com.turngame.factory.map.MapFactoryProvider;
import com.turngame.factory.skill.SkillFactoryProvider;
import com.turngame.replay.ReplayRecorder;
import com.turngame.server.account.AccountStore;
import com.turngame.server.protocol.RequestMessage;
import com.turngame.server.protocol.ResponseMessage;

import jakarta.websocket.Session;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.glassfish.tyrus.server.Server;
import jakarta.websocket.DeploymentException;

public class HttpRelayServer {
    private static final Gson GSON = new Gson();
    private static final long EVENT_WAIT_TIMEOUT_MS = 25_000L;

    private final int port;
    private final int turnTimeoutSeconds;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AccountStore accountStore = AccountStore.shared();
    private final Map<String, RelayPlayer> players = new ConcurrentHashMap<>();
    private final Queue<String> waitingPlayers = new ConcurrentLinkedQueue<>();
    private final Map<String, RelayMatch> matches = new ConcurrentHashMap<>();
    private final Map<String, String> playerToMatch = new ConcurrentHashMap<>();
    private final Map<String, Session> wsSessions = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile int playerSeq = 1;
    private volatile HttpServer httpServer;
    private volatile Server wsServer;

    public HttpRelayServer(int port, int turnTimeoutSeconds) {
        this.port = port;
        this.turnTimeoutSeconds = turnTimeoutSeconds;
    }

    public void start() throws IOException {
        running = true;
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/api/join", new JoinHandler());
        httpServer.createContext("/api/action", new ActionHandler());
        httpServer.createContext("/api/events", new EventsHandler());
        httpServer.createContext("/api/events/stream", new EventStreamHandler());
        httpServer.createContext("/api/disconnect", new DisconnectHandler());
        httpServer.createContext("/health", exchange -> writeJson(exchange, 200, Map.of("ok", true)));
        httpServer.setExecutor(executor);
        httpServer.start();
        System.out.println("HttpRelayServer started on port " + port);

        // Start WebSocket server on port+1 (e.g., 9091)
        try {
            GameWebSocketEndpoint.setRelayServer(this);
            wsServer = new Server("localhost", port + 1, "/", null, GameWebSocketEndpoint.class);
            wsServer.start();
            System.out.println("WebSocket server started on port " + (port + 1));
        } catch (DeploymentException ex) {
            System.err.println("WebSocket server startup failed: " + ex.getMessage());
            wsServer = null;
        }
    }

    public void stop() {
        System.out.println("Shutting down HttpRelayServer...");
        running = false;
        
        // 1. WebSocket 서버 종료
        if (wsServer != null) {
            wsServer.stop();
            System.out.println("WebSocket server stopped");
        }
        
        // 2. HttpServer 종료 (새 요청 거부)
        if (httpServer != null) {
            httpServer.stop(10);  // 10초 대기
            System.out.println("HttpServer stopped");
        }
        
        // 3. 활성 연결 정리
        cleanupPlayers();
        
        // 4. 진행 중인 매치 정리
        cleanupMatches();
        
        // 5. ThreadPool 종료
        executor.shutdownNow();
        scheduler.shutdownNow();
        
        try {
            // ThreadPool 종료 대기 (최대 10초)
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("Executor did not terminate in time");
            }
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("Scheduler did not terminate in time");
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for executor shutdown");
            Thread.currentThread().interrupt();
        }
        
        System.out.println("HttpRelayServer shutdown complete");
    }
    
    /**
     * 활성 플레이어 정보 정리
     */
    private void cleanupPlayers() {
        System.out.println("Cleaning up " + players.size() + " players");
        players.clear();
        waitingPlayers.clear();
        playerToMatch.clear();
    }
    
    /**
     * 진행 중인 매치 정리
     */
    private void cleanupMatches() {
        System.out.println("Cleaning up " + matches.size() + " matches");
        matches.forEach((matchId, match) -> {
            if (match.game != null) {
                System.out.println("  - Match " + matchId + " ended");
            }
        });
        matches.clear();
    }

    /**
     * WebSocket 세션 등록 (플레이어 단위)
     */
    public void registerWebSocketSession(String playerId, Session wsSession) {
        if (playerId != null && wsSession != null) {
            wsSessions.put(playerId, wsSession);
            System.out.println("WebSocket session registered for " + playerId);
        }
    }

    /**
     * WebSocket 세션 해제
     */
    public void unregisterWebSocketSession(String playerId) {
        wsSessions.remove(playerId);
    }

    /**
     * WebSocket을 통해 액션 제출 (기존 ActionHandler와 동일 로직)
     */
    public void submitGameAction(String matchId, String playerId, GameAction action) {
        String actualMatchId = playerToMatch.get(playerId);
        if (matchId == null || !matchId.equals(actualMatchId)) {
            return;
        }

        RelayMatch match = matches.get(matchId);
        if (match == null) {
            return;
        }

        try {
            match.game.submitAction(action);
            if (match.game.consumeWindowAdvancedFlag()) {
                match.game.getAllPlayerIds().forEach(pid -> tickCooldowns(matchId, pid));
            }

            publishState(match, "ws-action");
            if (match.game.isFinished()) {
                publishGameEnded(match, "ws-action");
                clearMatch(matchId);
            } else {
                scheduleTurnTimeout(matchId);
            }
        } catch (RuntimeException ex) {
            System.err.println("WebSocket action error: " + ex.getMessage());
        }
    }

    /**
     * WebSocket 연결 단절 알림 (기존 /api/disconnect와 유사)
     */
    public void notifyDisconnect(String playerId) {
        handlePlayerDisconnect(playerId, "system");
    }

    private void handlePlayerDisconnect(String playerId, String requestId) {
        if (playerId == null || playerId.isBlank()) {
            return;
        }

        RelayPlayer player = players.get(playerId);
        if (player == null) {
            return;
        }

        player.connected = false;
        waitingPlayers.removeIf(id -> id.equals(playerId));
        unregisterWebSocketSession(playerId);

        String matchId = player.matchId;
        if (matchId == null || matchId.isBlank()) {
            return;
        }

        RelayMatch match = matches.get(matchId);
        if (match == null) {
            return;
        }

        Map<String, Object> disconnectedPayload = new HashMap<>();
        disconnectedPayload.put("matchId", matchId);
        disconnectedPayload.put("playerId", playerId);
        publishToMatch(matchId, new ResponseMessage("PLAYER_DISCONNECTED", requestId, disconnectedPayload));

        String winnerId = null;
        for (String pid : match.game.getAllPlayerIds()) {
            if (!playerId.equals(pid)) {
                winnerId = pid;
                break;
            }
        }
        Map<String, Object> endedPayload = new HashMap<>();
        endedPayload.put("matchId", matchId);
        endedPayload.put("winnerId", winnerId);
        endedPayload.put("reason", "PLAYER_DISCONNECTED");
        publishToMatch(matchId, new ResponseMessage("GAME_ENDED", requestId, endedPayload));

        clearMatch(matchId);
    }

    private final class JoinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }

                RequestMessage req = readRequest(exchange);
                Map<String, Object> payload = req.payload() == null ? Map.of() : req.payload();
                String accountId = asString(payload.get("accountId"), "");
                String nickname = asString(payload.get("nickname"), "player");
                String characterType = asString(payload.get("characterType"), "WARRIOR");
                String characterDisplayName = asString(payload.get("characterDisplayName"), nickname);
                int turnEnergyCap = asInt(payload.get("turnEnergyCap"), Integer.MAX_VALUE);
                List<SkillTemplate> customSkills = parseCustomSkills(payload.get("skills"));

                if (!accountId.isBlank()) {
                    accountId = accountId.trim().toLowerCase(Locale.ROOT);
                    nickname = accountStore.nickname(accountId).orElse(nickname);
                    characterDisplayName = accountStore.characterProfile(accountId)
                            .map(AccountStore.CharacterProfile::displayName)
                            .orElse(characterDisplayName);
                }
                if (characterDisplayName == null || characterDisplayName.isBlank()) {
                    characterDisplayName = nickname;
                }

                String playerId = "p-" + (playerSeq++);
                RelayPlayer player = new RelayPlayer(
                    playerId,
                    nickname,
                    characterDisplayName,
                    accountId,
                    characterType.toUpperCase(Locale.ROOT),
                    customSkills,
                    turnEnergyCap
                );
                players.put(playerId, player);
                
                // FIND_GAME 또는 JOIN: 두 요청 모두 매칭 큐에 추가
                if ("FIND_GAME".equalsIgnoreCase(req.type()) || "JOIN".equalsIgnoreCase(req.type())) {
                    waitingPlayers.offer(playerId);
                }

                Map<String, Object> responsePayload = new HashMap<>();
                responsePayload.put("playerId", playerId);
                responsePayload.put("nickname", nickname);
                responsePayload.put("characterDisplayName", characterDisplayName);
                responsePayload.put("accountId", accountId.isBlank() ? null : accountId);
                responsePayload.put("characterType", characterType.toUpperCase(Locale.ROOT));
                tryCreateMatch();
                writeJson(exchange, 200, new ResponseMessage("JOINED", req.requestId(), responsePayload));
            } catch (RuntimeException ex) {
                writeJson(exchange, 400, error(readRequest(exchange).requestId(), "JOIN_FAILED", ex.getMessage()));
            }
        }
    }

    private final class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }

                RequestMessage req = readRequest(exchange);
                String playerId = asString(req.payload() == null ? null : req.payload().get("playerId"), "");
                if (playerId.isBlank()) {
                    writeJson(exchange, 400, error(req.requestId(), "NOT_JOINED", "Missing playerId."));
                    return;
                }

                String matchId = playerToMatch.get(playerId);
                if (matchId == null) {
                    writeJson(exchange, 400, error(req.requestId(), "NOT_MATCHED", "Player is not in a match."));
                    return;
                }

                String requestMatchId = asString(req.payload().get("matchId"), "");
                if (!matchId.equals(requestMatchId)) {
                    writeJson(exchange, 400, error(req.requestId(), "MATCH_MISMATCH", "matchId does not match your current game."));
                    return;
                }

                RelayMatch match = matches.get(matchId);
                if (match == null) {
                    writeJson(exchange, 404, error(req.requestId(), "MATCH_NOT_FOUND", "Match not found."));
                    return;
                }

                try {
                    GameAction action = parseAction(match, playerId, req.payload());
                    match.game.submitAction(action);
                    if (match.game.consumeWindowAdvancedFlag()) {
                        match.game.getAllPlayerIds().forEach(pid -> tickCooldowns(matchId, pid));
                    }

                    publishState(match, req.requestId());
                    if (match.game.isFinished()) {
                        publishGameEnded(match, req.requestId());
                        clearMatch(matchId);
                    } else {
                        scheduleTurnTimeout(matchId);
                    }
                    writeJson(exchange, 200, Map.of("ok", true));
                } catch (RuntimeException ex) {
                    writeJson(exchange, 400, error(req.requestId(), "INVALID_ACTION", ex.getMessage()));
                }
            } catch (RuntimeException ex) {
                writeJson(exchange, 400, error(readRequest(exchange).requestId(), "ACTION_FAILED", ex.getMessage()));
            }
        }
    }

    private final class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }

                Map<String, String> query = queryParams(exchange.getRequestURI());
                String playerId = query.getOrDefault("playerId", "");
                long after = parseLong(query.getOrDefault("after", "0"), 0L);
                RelayPlayer player = players.get(playerId);
                if (player == null) {
                    writeJson(exchange, 404, Map.of("error", "Unknown playerId"));
                    return;
                }

                List<Map<String, Object>> events = waitForEvents(player, after);
                Map<String, Object> response = new HashMap<>();
                response.put("events", events);
                response.put("nextSeq", player.lastDeliveredSeq);
                writeJson(exchange, 200, response);
            } catch (RuntimeException ex) {
                writeJson(exchange, 400, Map.of("error", ex.getMessage()));
            }
        }
    }

    private final class EventStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }

            Map<String, String> query = queryParams(exchange.getRequestURI());
            String playerId = query.getOrDefault("playerId", "");
            long after = parseLong(query.getOrDefault("after", "0"), 0L);
            RelayPlayer player = players.get(playerId);
            if (player == null) {
                writeJson(exchange, 404, Map.of("error", "Unknown playerId"));
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
                writer.write("retry: 1500\n\n");
                writer.flush();

                while (running && player.connected) {
                    List<Map<String, Object>> events = waitForEvents(player, after);
                    if (events.isEmpty()) {
                        writer.write(": keepalive\n\n");
                        writer.flush();
                        continue;
                    }

                    for (Map<String, Object> eventEnvelope : events) {
                        Object seqObj = eventEnvelope.get("seq");
                        long seq = asLong(seqObj, after);
                        writer.write("id: " + seq + "\n");
                        writer.write("event: game\n");
                        writer.write("data: " + GSON.toJson(eventEnvelope) + "\n\n");
                        after = seq;
                    }
                    writer.flush();
                }
            } catch (IOException ignored) {
                // client disconnected
            }
        }
    }

    private final class DisconnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, Map.of("error", "Method not allowed"));
                    return;
                }

                RequestMessage req = readRequest(exchange);
                Map<String, Object> payload = req.payload() == null ? Map.of() : req.payload();
                String playerId = asString(payload.get("playerId"), "");
                if (playerId.isBlank()) {
                    writeJson(exchange, 400, error(req.requestId(), "MISSING_PLAYER", "playerId required"));
                    return;
                }

                RelayPlayer player = players.get(playerId);
                if (player == null) {
                    writeJson(exchange, 404, error(req.requestId(), "UNKNOWN_PLAYER", "player not found"));
                    return;
                }

                // mark disconnected and remove from waiting queue if present
                handlePlayerDisconnect(playerId, req.requestId());

                // do not remove from players map immediately to allow reconnection within timeout
                writeJson(exchange, 200, Map.of("ok", true));
            } catch (RuntimeException ex) {
                writeJson(exchange, 400, error(readRequest(exchange).requestId(), "DISCONNECT_FAILED", ex.getMessage()));
            }
        }
    }

    private List<Map<String, Object>> waitForEvents(RelayPlayer player, long after) {
        long deadline = System.currentTimeMillis() + EVENT_WAIT_TIMEOUT_MS;
        synchronized (player.lock) {
            while (!hasEventsAfter(player, after) && running) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    player.lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            List<Map<String, Object>> events = new ArrayList<>();
            for (QueuedEvent event : player.events) {
                if (event.seq > after) {
                    events.add(toEnvelope(event.seq, event.message));
                    player.lastDeliveredSeq = Math.max(player.lastDeliveredSeq, event.seq);
                }
            }
            trimEvents(player);
            return events;
        }
    }

    public List<Map<String, Object>> snapshotEventsAfter(String playerId, long after) {
        RelayPlayer player = players.get(playerId);
        if (player == null) {
            return List.of();
        }

        synchronized (player.lock) {
            List<Map<String, Object>> events = new ArrayList<>();
            for (QueuedEvent event : player.events) {
                if (event.seq > after) {
                    events.add(toEnvelope(event.seq, event.message));
                    player.lastDeliveredSeq = Math.max(player.lastDeliveredSeq, event.seq);
                }
            }
            trimEvents(player);
            return events;
        }
    }

    private boolean hasEventsAfter(RelayPlayer player, long after) {
        for (QueuedEvent event : player.events) {
            if (event.seq > after) {
                return true;
            }
        }
        return false;
    }

    private void trimEvents(RelayPlayer player) {
        if (player.events.size() <= 128) {
            return;
        }
        long floor = Math.max(player.lastDeliveredSeq - 32, 0);
        player.events.removeIf(event -> event.seq < floor);
    }

    private synchronized void tryCreateMatch() {
        if (waitingPlayers.size() < 2) {
            return;
        }

        String p1 = waitingPlayers.poll();
        String p2 = waitingPlayers.poll();
        if (p1 == null || p2 == null || !players.containsKey(p1) || !players.containsKey(p2)) {
            if (p1 != null && players.containsKey(p1)) {
                waitingPlayers.offer(p1);
            }
            if (p2 != null && players.containsKey(p2)) {
                waitingPlayers.offer(p2);
            }
            return;
        }

        String matchId = "m-" + UUID.randomUUID();
        EventBus eventBus = new EventBus();
        BattleMap map = MapFactoryProvider.randomFactory().createMap();
        GameCharacter c1 = CharacterFactoryProvider.byPreference(players.get(p1).characterType).createCharacter(p1);
        GameCharacter c2 = CharacterFactoryProvider.byPreference(players.get(p2).characterType).createCharacter(p2);
        List<SkillTemplate> skills1 = preferredSkillsOrStarter(players.get(p1), c1);
        List<SkillTemplate> skills2 = preferredSkillsOrStarter(players.get(p2), c2);
        ReplayRecorder replay = new ReplayRecorder(matchId);
        eventBus.subscribe(ActionAppliedEvent.class,
                e -> replay.record("ACTION_APPLIED", Map.of("matchId", e.matchId(), "actionType", e.action().actionType().name(), "actorId", e.action().actorId())));
        eventBus.subscribe(TurnStartedEvent.class,
                e -> replay.record("TURN_STARTED", Map.of("matchId", e.matchId(), "playerId", e.playerId())));
        eventBus.subscribe(GameEndedEvent.class,
                e -> replay.record("GAME_ENDED", Map.of("matchId", e.matchId(), "winnerId", e.winnerId())));

        RelayMatch match = new RelayMatch(matchId, new GameSession(matchId, new BasicRuleSet(), new TurnManager(List.of(p1, p2)), eventBus, map), map, replay);
        match.game.addPlayer(p1, c1);
        match.game.addPlayer(p2, c2);
        int p1TurnCap = resolveTurnEnergyCap(players.get(p1));
        int p2TurnCap = resolveTurnEnergyCap(players.get(p2));
        match.game.getPlayerState(p1).setMaxEnergySpendPerWindow(p1TurnCap);
        match.game.getPlayerState(p2).setMaxEnergySpendPerWindow(p2TurnCap);
        match.characterByPlayer.put(p1, c1);
        match.characterByPlayer.put(p2, c2);
        skills1.forEach(skill -> {
            match.game.registerSkill(skill);
            match.skillCooldowns.put(cooldownKey(matchId, p1, skill.name()), 0);
        });
        skills2.forEach(skill -> {
            match.game.registerSkill(skill);
            match.skillCooldowns.put(cooldownKey(matchId, p2, skill.name()), 0);
        });
        match.playerSkills.put(p1, skills1);
        match.playerSkills.put(p2, skills2);
        matches.put(matchId, match);
        playerToMatch.put(p1, matchId);
        playerToMatch.put(p2, matchId);
        players.get(p1).matchId = matchId;
        players.get(p2).matchId = matchId;
        System.out.println("[HttpRelayServer] match created matchId=" + matchId + " players=" + p1 + "," + p2);

        List<String> matchedPlayers = List.of(p1, p2);
        
        // 1단계: 두 플레이어에게 MATCHED 알림
        for (String playerId : matchedPlayers) {
            Map<String, Object> matchedPayload = new HashMap<>();
            matchedPayload.put("matchId", matchId);
            matchedPayload.put("playerId", playerId);
            publish(playerId, new ResponseMessage("MATCHED", "system", matchedPayload));
        }
        System.out.println("[HttpRelayServer] MATCHED published for matchId=" + matchId);

        // 2단계: 게임 시작 데이터와 함께 MATCH_STARTED 전송
        for (String playerId : matchedPlayers) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("matchId", matchId);
            payload.put("playerId", playerId);
            
            // 두 플레이어 모두에게 모든 플레이어 데이터 전달
            List<Map<String, Object>> allPlayers = new ArrayList<>();
            for (String pid : matchedPlayers) {
                Map<String, Object> pdata = new HashMap<>();
                pdata.put("playerId", pid);
                pdata.put("character", pid.equals(p1) ? c1 : c2);
                pdata.put("skills", pid.equals(p1) ? skills1 : skills2);
                RelayPlayer relayPlayer = players.get(pid);
                pdata.put("nickname", relayPlayer == null ? pid : relayPlayer.nickname);
                pdata.put("characterDisplayName", relayPlayer == null ? pid : relayPlayer.characterDisplayName);
                allPlayers.add(pdata);
            }
            
            payload.put("players", allPlayers);
            payload.put("map", map);
            payload.put("character", playerId.equals(p1) ? c1 : c2);
            payload.put("skills", playerId.equals(p1) ? skills1 : skills2);
            RelayPlayer relayPlayer = players.get(playerId);
            payload.put("nickname", relayPlayer == null ? playerId : relayPlayer.nickname);
            payload.put("characterDisplayName", relayPlayer == null ? playerId : relayPlayer.characterDisplayName);
            payload.put("firstTurnPlayerId", match.game.getCurrentPlayerId());
            publish(playerId, new ResponseMessage("MATCH_STARTED", "system", payload));
        }
        System.out.println("[HttpRelayServer] MATCH_STARTED published for matchId=" + matchId);

        publishState(match, "system");
        scheduleTurnTimeout(matchId);
    }

    private void scheduleTurnTimeout(String matchId) {
        RelayMatch match = matches.get(matchId);
        if (match == null) {
            return;
        }

        if (match.timeoutFuture != null) {
            match.timeoutFuture.cancel(true);
        }

        match.timeoutFuture = scheduler.schedule(() -> {
            RelayMatch current = matches.get(matchId);
            if (current == null || current.game.isFinished()) {
                return;
            }
            try {
                // Force-complete this window on timeout by ending turn for every unready player.
                boolean anyTimedOut = false;
                for (String playerId : current.game.getAllPlayerIds()) {
                    if (current.game.isPlayerReady(playerId)) {
                        continue;
                    }
                    current.game.submitAction(new EndTurnAction(playerId));
                    anyTimedOut = true;
                }

                if (anyTimedOut) {
                    publishState(current, "timeout");
                    if (current.game.isFinished()) {
                        publishGameEnded(current, "timeout");
                        clearMatch(matchId);
                    }
                }
            } catch (RuntimeException ignored) {
                // timeout fallback only
            }
        }, turnTimeoutSeconds, TimeUnit.SECONDS);
    }

    private void clearMatch(String matchId) {
        RelayMatch match = matches.remove(matchId);
        if (match != null && match.timeoutFuture != null) {
            match.timeoutFuture.cancel(true);
        }
        for (RelayPlayer player : players.values()) {
            if (matchId.equals(player.matchId)) {
                player.matchId = null;
            }
        }
        playerToMatch.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), matchId));
    }

    private void tickCooldowns(String matchId, String playerId) {
        RelayMatch match = matches.get(matchId);
        if (match == null) {
            return;
        }
        List<SkillTemplate> skills = match.playerSkills.getOrDefault(playerId, List.of());
        for (SkillTemplate skill : skills) {
            String key = cooldownKey(matchId, playerId, skill.name());
            int next = Math.max(0, match.skillCooldowns.getOrDefault(key, 0) - 1);
            match.skillCooldowns.put(key, next);
        }
    }

    private GameAction parseAction(RelayMatch match, String actorId, Map<String, Object> payload) {
        String actionType = asString(payload.get("actionType"), "").toUpperCase(Locale.ROOT);
        ActionType type = ActionType.valueOf(actionType);
        if (type == ActionType.ATTACK) {
            String targetId = asString(payload.get("targetId"), "");
            int damage = asInt(payload.get("damage"), 20);
            return new AttackAction(actorId, targetId, damage);
        }
        if (type == ActionType.DEFEND) {
            String evadeSkillName = asString(payload.get("evadeSkillName"), "Dodge");
            long evadeStartTimeMs = asLong(payload.get("evadeStartTimeMs"), System.currentTimeMillis());
            return new DefendAction(actorId, evadeSkillName, evadeStartTimeMs);
        }
        if (type == ActionType.USE_SKILL) {
            String targetId = asString(payload.get("targetId"), "");
            String skillName = asString(payload.get("skillName"), "").trim();
            SkillTemplate skill = findOwnedSkill(match, actorId, skillName);
            if (skill == null) {
                throw new IllegalArgumentException("Unknown or unowned skill: " + skillName);
            }
            int cooldown = match.skillCooldowns.getOrDefault(cooldownKey(match.matchId, actorId, skill.name()), 0);
            if (cooldown > 0) {
                throw new IllegalArgumentException("Skill cooldown remaining: " + cooldown);
            }
            GameCharacter character = match.characterByPlayer.get(actorId);
            int attackBonus = character == null ? 0 : character.attackBonus();
            int computedDamage = Math.max(5, Math.min(80, 10 + attackBonus + skill.baseDamage()));
            match.skillCooldowns.put(cooldownKey(match.matchId, actorId, skill.name()), skill.cooldownTurns());
            Integer targetCol = payload.get("targetCol") == null ? null : asInt(payload.get("targetCol"), 0);
            Integer targetRow = payload.get("targetRow") == null ? null : asInt(payload.get("targetRow"), 0);
            return new UseSkillAction(actorId, targetId, skill.name(), computedDamage, targetCol, targetRow);
        }
        if (type == ActionType.MOVE) {
            int targetCol = asInt(payload.get("targetCol"), -1);
            int targetRow = asInt(payload.get("targetRow"), -1);
            long requestedAtMs = System.currentTimeMillis();
            return new MoveAction(actorId, targetCol, targetRow, requestedAtMs);
        }
        return new EndTurnAction(actorId);
    }

    private SkillTemplate findOwnedSkill(RelayMatch match, String playerId, String skillName) {
        for (SkillTemplate skill : match.playerSkills.getOrDefault(playerId, List.of())) {
            if (skill.name().equalsIgnoreCase(skillName)) {
                return skill;
            }
        }
        return null;
    }

    private void publishState(RelayMatch match, String requestId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("matchId", match.matchId);
        payload.put("turnPlayerId", match.game.getCurrentPlayerId());
        payload.put("windowIndex", match.game.getCurrentWindowIndex());
        payload.put("windowDurationSeconds", match.game.getWindowDurationSeconds());
        payload.put("readyPlayers", match.game.getReadyPlayers());
        payload.put("resolvedWindowIndex", match.game.getLastResolvedWindowIndex());
        payload.put("resolutionSteps", match.game.snapshotLastResolutionSteps());
        payload.put("map", match.map);
        payload.put("mapRows", match.game.getMapRows());
        payload.put("mapCols", match.game.getMapCols());

        List<Map<String, Object>> playersPayload = new ArrayList<>();
        match.game.snapshotPlayerStates().forEach((playerId, state) -> {
            Map<String, Object> p = new HashMap<>();
            p.put("playerId", playerId);
            RelayPlayer relayPlayer = players.get(playerId);
            if (relayPlayer != null) {
                p.put("nickname", relayPlayer.nickname);
                p.put("characterDisplayName", relayPlayer.characterDisplayName);
            }
            p.put("hp", state.hp());
            p.put("maxHp", state.maxHp());
            p.put("defending", state.isDefending());
            p.put("alive", state.isAlive());
            p.put("strength", state.strength());
            p.put("agility", state.agility());
            p.put("intelligence", state.intelligence());
            p.put("energy", state.energy());
            p.put("maxEnergy", state.maxEnergy());
            p.put("energySpentInWindow", state.energySpentInWindow());
            p.put("maxEnergySpendPerWindow", state.maxEnergySpendPerWindow());
            match.game.getPlayerPosition(playerId).ifPresent(pos -> {
                p.put("mapCol", pos.col());
                p.put("mapRow", pos.row());
            });
            GameCharacter character = match.characterByPlayer.get(playerId);
            if (character != null) {
                p.put("characterType", character.type().name());
                p.put("characterTitle", character.title());
                p.put("attackBonus", character.attackBonus());
            }
            p.put("skills", match.playerSkills.getOrDefault(playerId, List.of()));
            playersPayload.add(p);
        });
        payload.put("players", playersPayload);

        publishToMatch(match.matchId, new ResponseMessage("STATE_UPDATED", requestId, payload));
    }

    private void publishGameEnded(RelayMatch match, String requestId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("matchId", match.matchId);
        payload.put("winnerId", match.game.getWinnerId());
        payload.put("reason", "HP_ZERO");
        publishToMatch(match.matchId, new ResponseMessage("GAME_ENDED", requestId, payload));
    }

    private void publishToMatch(String matchId, ResponseMessage message) {
        for (Map.Entry<String, String> entry : playerToMatch.entrySet()) {
            if (!Objects.equals(entry.getValue(), matchId)) {
                continue;
            }
            publish(entry.getKey(), message);
        }
    }

    private void publish(String playerId, ResponseMessage message) {
        RelayPlayer player = players.get(playerId);
        if (player == null) {
            return;
        }
        long seq;
        synchronized (player.lock) {
            seq = player.nextSeq++;
            player.events.add(new QueuedEvent(seq, message));
            player.lock.notifyAll();
        }

        // 동시에 WebSocket 연결이 있으면 전송
        Session wsSession = wsSessions.get(playerId);
        if (wsSession != null && wsSession.isOpen()) {
            try {
                Map<String, Object> envelope = toEnvelope(seq, message);
                wsSession.getBasicRemote().sendText(GSON.toJson(envelope));
            } catch (Exception ex) {
                System.err.println("WebSocket send error for " + playerId + ": " + ex.getMessage());
                unregisterWebSocketSession(playerId);
            }
        }
    }

    private ResponseMessage error(String requestId, String code, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        return new ResponseMessage("ERROR", requestId, payload);
    }

    private RequestMessage readRequest(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            RequestMessage req = GSON.fromJson(body.toString(), RequestMessage.class);
            if (req == null) {
                throw new IllegalArgumentException("Empty request body");
            }
            return req;
        }
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> queryParams(URI uri) {
        Map<String, String> query = new HashMap<>();
        if (uri.getQuery() == null || uri.getQuery().isBlank()) {
            return query;
        }
        for (String pair : uri.getQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                query.put(pair, "");
            } else {
                query.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return query;
    }

    private Map<String, Object> toEnvelope(long seq, ResponseMessage message) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("seq", seq);
        envelope.put("type", message.type());
        envelope.put("requestId", message.requestId());
        envelope.put("payload", message.payload());
        return envelope;
    }

    private String cooldownKey(String matchId, String playerId, String skillName) {
        return matchId + "::" + playerId + "::" + skillName;
    }

    private void clearTimeout(String matchId) {
        RelayMatch match = matches.get(matchId);
        if (match != null && match.timeoutFuture != null) {
            match.timeoutFuture.cancel(true);
        }
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

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private int resolveTurnEnergyCap(RelayPlayer player) {
        if (player == null || player.turnEnergyCap <= 0) {
            return Integer.MAX_VALUE;
        }
        return player.turnEnergyCap;
    }

    private List<SkillTemplate> preferredSkillsOrStarter(RelayPlayer player, GameCharacter character) {
        if (player != null && player.customSkills != null && !player.customSkills.isEmpty()) {
            return List.copyOf(player.customSkills);
        }
        return SkillFactoryProvider.byCharacterType(character.type()).createStarterSkills();
    }

    private List<SkillTemplate> parseCustomSkills(Object skillsObj) {
        if (!(skillsObj instanceof List<?> rawSkills)) {
            return List.of();
        }
        List<SkillTemplate> parsed = new ArrayList<>();
        for (Object skillObj : rawSkills) {
            SkillTemplate template = parseSkillTemplate(skillObj);
            if (template != null) {
                parsed.add(template);
            }
        }
        return List.copyOf(parsed);
    }

    @SuppressWarnings("unchecked")
    private SkillTemplate parseSkillTemplate(Object skillObj) {
        if (!(skillObj instanceof Map<?, ?> rawSkill)) {
            return null;
        }
        Map<String, Object> skillData = (Map<String, Object>) rawSkill;
        String name = asString(skillData.get("name"), "").trim();
        if (name.isBlank()) {
            return null;
        }

        int baseDamage = asInt(skillData.get("baseDamage"), 0);
        int cooldownTurns = asInt(skillData.get("cooldownTurns"), 0);
        double baseSuccessProbability = asDouble(skillData.get("baseSuccessProbability"), 1.0);
        int failEnergyCost = Math.max(0, asInt(skillData.get("failEnergyCost"), 0));
        int successEnergyCost = Math.max(0, asInt(skillData.get("successEnergyCost"), 0));
        int prepareCastMs = Math.max(0, asInt(skillData.get("prepareCastMs"), 0));
        boolean isDefenseSkill = asBoolean(skillData.get("isDefenseSkill"));
        int evadeDurationMs = Math.max(0, asInt(skillData.get("evadeDurationMs"), 0));

        SkillEffect effect = new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0);
        Object effectObj = skillData.get("effect");
        if (effectObj instanceof Map<?, ?> rawEffect) {
            Map<String, Object> effectData = (Map<String, Object>) rawEffect;
            String areaTypeName = asString(effectData.get("areaType"), "STATIC").trim().toUpperCase(Locale.ROOT);
            SkillEffect.AreaType areaType;
            try {
                areaType = SkillEffect.AreaType.valueOf(areaTypeName);
            } catch (IllegalArgumentException ex) {
                areaType = SkillEffect.AreaType.STATIC;
            }
                List<String> areaPatternRows = asStringList(effectData.get("areaPatternRows"));
            effect = new SkillEffect(
                    areaType,
                    Math.max(0, asInt(effectData.get("areaRadius"), 1)),
                    Math.max(0, asInt(effectData.get("durationTurns"), 0)),
                    areaPatternRows
            );
        }

        return new SkillTemplate(
                name,
                baseDamage,
                cooldownTurns,
                baseSuccessProbability,
                failEnergyCost,
                successEnergyCost,
                prepareCastMs,
                effect,
                List.of(),
                List.of(),
                isDefenseSkill,
                evadeDurationMs
        );
    }

    private static double asDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (Object item : rawList) {
            if (item != null) {
                list.add(String.valueOf(item));
            }
        }
        return List.copyOf(list);
    }

    private static final class RelayPlayer {
        final String playerId;
        final String nickname;
        final String characterDisplayName;
        final String accountId;
        final String characterType;
        final List<SkillTemplate> customSkills;
        final int turnEnergyCap;
        final Object lock = new Object();
        final List<QueuedEvent> events = new ArrayList<>();
        volatile long nextSeq = 1L;
        volatile long lastDeliveredSeq = 0L;
        volatile String matchId;
        volatile boolean connected = true;

        private RelayPlayer(
                String playerId,
                String nickname,
                String characterDisplayName,
                String accountId,
                String characterType,
                List<SkillTemplate> customSkills,
                int turnEnergyCap
        ) {
            this.playerId = playerId;
            this.nickname = nickname;
            this.characterDisplayName = characterDisplayName;
            this.accountId = accountId;
            this.characterType = characterType;
            this.customSkills = customSkills == null ? List.of() : List.copyOf(customSkills);
            this.turnEnergyCap = turnEnergyCap;
        }
    }

    private static final class QueuedEvent {
        final long seq;
        final ResponseMessage message;

        private QueuedEvent(long seq, ResponseMessage message) {
            this.seq = seq;
            this.message = message;
        }
    }

    private static final class RelayMatch {
        final String matchId;
        final GameSession game;
        final BattleMap map;
        final ReplayRecorder replayRecorder;
        final Map<String, List<SkillTemplate>> playerSkills = new ConcurrentHashMap<>();
        final Map<String, Integer> skillCooldowns = new ConcurrentHashMap<>();
        final Map<String, GameCharacter> characterByPlayer = new ConcurrentHashMap<>();
        volatile ScheduledFuture<?> timeoutFuture;

        private RelayMatch(String matchId, GameSession game, BattleMap map, ReplayRecorder replayRecorder) {
            this.matchId = matchId;
            this.game = game;
            this.map = map;
            this.replayRecorder = replayRecorder;
        }
    }
}
