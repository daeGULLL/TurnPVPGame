package com.turngame.server;

import com.turngame.domain.PlayerState;
import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.map.BattleMap;
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
import com.turngame.event.EventBus;
import com.turngame.event.GameEndedEvent;
import com.turngame.event.TurnStartedEvent;
import com.turngame.event.ActionAppliedEvent;
import com.turngame.factory.character.CharacterFactoryProvider;
import com.turngame.factory.map.MapFactoryProvider;
import com.turngame.factory.skill.SkillFactoryProvider;
import com.turngame.replay.ReplayRecorder;
import com.turngame.server.account.AccountStore;
import com.turngame.server.protocol.MessageCodec;
import com.turngame.server.protocol.RequestMessage;
import com.turngame.server.protocol.ResponseMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GameServer {
    private final int port;
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final Queue<String> waitingPlayers = new ConcurrentLinkedQueue<>();
    private final Map<String, GameSession> matches = new ConcurrentHashMap<>();
    private final Map<String, ReplayRecorder> replayRecorders = new ConcurrentHashMap<>();
    private final Map<String, String> playerToMatch = new ConcurrentHashMap<>();
    private final Map<String, String> playerAccounts = new ConcurrentHashMap<>();
    private final Map<String, String> playerCharacterPrefs = new ConcurrentHashMap<>();
    private final Map<String, GameCharacter> playerCharacters = new ConcurrentHashMap<>();
    private final Map<String, List<SkillTemplate>> playerSkills = new ConcurrentHashMap<>();
    private final Map<String, Integer> skillCooldowns = new ConcurrentHashMap<>();
    private final Map<String, BattleMap> matchMaps = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> matchTimeouts = new ConcurrentHashMap<>();
    private final AccountStore accountStore = AccountStore.shared();
    private volatile boolean running;
    private volatile int playerSeq = 1;
    private final int turnTimeoutSeconds;

    public GameServer(int port) {
        this(port, 20);
    }

    public GameServer(int port, int turnTimeoutSeconds) {
        this.port = port;
        this.turnTimeoutSeconds = turnTimeoutSeconds;
    }

    public void start() throws IOException {
        running = true;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("GameServer started on port " + port);
            while (running) {
                Socket socket = serverSocket.accept();
                clientPool.submit(() -> handleClient(socket));
            }
        }
    }

    public void stop() {
        running = false;
        clientPool.shutdownNow();
        scheduler.shutdownNow();
        matchTimeouts.values().forEach(future -> future.cancel(true));
    }

    private void handleClient(Socket socket) {
        String playerId = null;
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                RequestMessage req = MessageCodec.decodeRequest(line);
                if ("ACCOUNT_CREATE".equalsIgnoreCase(req.type())) {
                    handleAccountCreate(req, writer);
                    continue;
                }

                if ("ACCOUNT_LOGIN".equalsIgnoreCase(req.type())) {
                    handleAccountLogin(req, writer);
                    continue;
                }

                if ("JOIN".equalsIgnoreCase(req.type())) {
                    playerId = handleJoin(req, socket, writer);
                    continue;
                }

                if (playerId == null) {
                    send(writer, error(req.requestId(), "NOT_JOINED", "Send JOIN first."));
                    continue;
                }

                if ("ACTION".equalsIgnoreCase(req.type())) {
                    handleAction(playerId, req, writer);
                } else {
                    send(writer, error(req.requestId(), "UNKNOWN_TYPE", "Unsupported message type."));
                }
            }
        } catch (IOException ignored) {
            // Connection closed by client.
        } finally {
            cleanupDisconnectedPlayer(playerId);
        }
    }

    private String handleJoin(RequestMessage req, Socket socket, PrintWriter out) {
        Map<String, Object> payload = req.payload();
        String accountId = asString(payload.get("accountId"), "");
        String nickname = asString(payload.get("nickname"), "player");
        String characterType = asString(payload.get("characterType"), "WARRIOR");
        if (!accountId.isBlank()) {
            accountId = accountId.trim().toLowerCase(Locale.ROOT);
            nickname = accountStore.nickname(accountId).orElse(nickname);
        }
        String playerId = "p-" + (playerSeq++);

        ClientSession session = new ClientSession(playerId, nickname, socket, out);
        clients.put(playerId, session);
        if (!accountId.isBlank()) {
            playerAccounts.put(playerId, accountId);
        }
        playerCharacterPrefs.put(playerId, characterType);
        waitingPlayers.offer(playerId);

        Map<String, Object> joinedPayload = new HashMap<>();
        joinedPayload.put("playerId", playerId);
        joinedPayload.put("nickname", nickname);
        joinedPayload.put("accountId", accountId.isBlank() ? null : accountId);
        joinedPayload.put("characterType", characterType.toUpperCase(Locale.ROOT));
        send(out, new ResponseMessage("JOINED", req.requestId(), joinedPayload));

        tryCreateMatch();
        return playerId;
    }

    private void handleAccountCreate(RequestMessage req, PrintWriter out) {
        Map<String, Object> payload = req.payload();
        String accountId = asString(payload.get("accountId"), "");
        String password = asString(payload.get("password"), "");
        String nickname = asString(payload.get("nickname"), "");

        boolean created = accountStore.createAccount(accountId, password, nickname);
        if (!created) {
            send(out, error(req.requestId(), "ACCOUNT_CREATE_FAILED", "Account already exists or input is invalid."));
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("accountId", accountId);
        response.put("nickname", accountStore.nickname(accountId).orElse(accountId));
        send(out, new ResponseMessage("ACCOUNT_CREATED", req.requestId(), response));
    }

    private void handleAccountLogin(RequestMessage req, PrintWriter out) {
        Map<String, Object> payload = req.payload();
        String accountId = asString(payload.get("accountId"), "");
        String password = asString(payload.get("password"), "");

        accountStore.login(accountId, password).ifPresentOrElse(session -> {
            Map<String, Object> response = new HashMap<>();
            response.put("accountId", session.accountId());
            response.put("nickname", session.nickname());
            response.put("progress", session.progress());
            send(out, new ResponseMessage("ACCOUNT_LOGGED_IN", req.requestId(), response));
        }, () -> send(out, error(req.requestId(), "ACCOUNT_LOGIN_FAILED", "Invalid account or password.")));
    }

    private synchronized void tryCreateMatch() {
        if (waitingPlayers.size() < 2) {
            return;
        }

        String p1 = waitingPlayers.poll();
        String p2 = waitingPlayers.poll();
        if (p1 == null || p2 == null || !clients.containsKey(p1) || !clients.containsKey(p2)) {
            if (p1 != null && clients.containsKey(p1)) {
                waitingPlayers.offer(p1);
            }
            if (p2 != null && clients.containsKey(p2)) {
                waitingPlayers.offer(p2);
            }
            return;
        }

        String matchId = "m-" + UUID.randomUUID();
        EventBus eventBus = new EventBus();
        BattleMap map = MapFactoryProvider.randomFactory().createMap();
        matchMaps.put(matchId, map);

        GameCharacter c1 = CharacterFactoryProvider.byPreference(playerCharacterPrefs.get(p1)).createCharacter(p1);
        GameCharacter c2 = CharacterFactoryProvider.byPreference(playerCharacterPrefs.get(p2)).createCharacter(p2);
        playerCharacters.put(p1, c1);
        playerCharacters.put(p2, c2);
        playerSkills.put(p1, SkillFactoryProvider.byCharacterType(c1.type()).createStarterSkills());
        playerSkills.put(p2, SkillFactoryProvider.byCharacterType(c2.type()).createStarterSkills());
        playerSkills.getOrDefault(p1, List.of()).forEach(skill -> skillCooldowns.put(cooldownKey(matchId, p1, skill.name()), 0));
        playerSkills.getOrDefault(p2, List.of()).forEach(skill -> skillCooldowns.put(cooldownKey(matchId, p2, skill.name()), 0));

        ReplayRecorder replay = new ReplayRecorder(matchId);
        replayRecorders.put(matchId, replay);
        eventBus.subscribe(ActionAppliedEvent.class,
            e -> replay.record("ACTION_APPLIED",
                Map.of(
                    "matchId", e.matchId(),
                    "actionType", e.action().actionType().name(),
                    "actorId", e.action().actorId())));
        eventBus.subscribe(TurnStartedEvent.class,
            e -> replay.record("TURN_STARTED", Map.of("matchId", e.matchId(), "playerId", e.playerId())));
        eventBus.subscribe(GameEndedEvent.class,
            e -> replay.record("GAME_ENDED", Map.of("matchId", e.matchId(), "winnerId", e.winnerId())));
        GameSession game = new GameSession(matchId, new BasicRuleSet(), new TurnManager(List.of(p1, p2)), eventBus, map);
        game.addPlayer(p1, c1);
        game.addPlayer(p2, c2);

        // 모든 스킬 템플릿 등록
        playerSkills.getOrDefault(p1, List.of()).forEach(game::registerSkill);
        playerSkills.getOrDefault(p2, List.of()).forEach(game::registerSkill);

        matches.put(matchId, game);
        playerToMatch.put(p1, matchId);
        playerToMatch.put(p2, matchId);

        List<String> players = List.of(p1, p2);
        for (String playerId : players) {
            ClientSession client = clients.get(playerId);
            if (client == null) {
                continue;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("matchId", matchId);
            payload.put("playerId", playerId);
            payload.put("players", players);
            payload.put("map", map);
            payload.put("character", playerCharacters.get(playerId));
            payload.put("skills", playerSkills.getOrDefault(playerId, List.of()));
            payload.put("firstTurnPlayerId", game.getCurrentPlayerId());
            send(client.out(), new ResponseMessage("MATCH_STARTED", "system", payload));
        }

        broadcastState(game, "system");
        scheduleTurnTimeout(matchId);
    }

    private void handleAction(String playerId, RequestMessage req, PrintWriter out) {
        String matchId = playerToMatch.get(playerId);
        if (matchId == null) {
            send(out, error(req.requestId(), "NOT_MATCHED", "Player is not in a match."));
            return;
        }

        String requestMatchId = asString(req.payload().get("matchId"), "");
        if (!matchId.equals(requestMatchId)) {
            send(out, error(req.requestId(), "MATCH_MISMATCH", "matchId does not match your current game."));
            return;
        }

        GameSession game = matches.get(matchId);
        if (game == null) {
            send(out, error(req.requestId(), "MATCH_NOT_FOUND", "Match not found."));
            return;
        }

        try {
            GameAction action = parseAction(playerId, matchId, req.payload());
            game.submitAction(action);
            if (game.consumeWindowAdvancedFlag()) {
                game.getAllPlayerIds().forEach(pid -> tickCooldowns(matchId, pid));
            }
            broadcastState(game, req.requestId());

            if (game.isFinished()) {
                broadcastGameEnded(game, req.requestId());
                clearTimeout(matchId);
                closeReplay(matchId);
                clearSkillCooldownsForMatch(matchId);
            } else {
                scheduleTurnTimeout(matchId);
            }
        } catch (RuntimeException ex) {
            send(out, error(req.requestId(), "INVALID_ACTION", ex.getMessage()));
        }
    }

    private GameAction parseAction(String actorId, String matchId, Map<String, Object> payload) {
        String actionType = asString(payload.get("actionType"), "").toUpperCase(Locale.ROOT);
        ActionType type;
        try {
            type = ActionType.valueOf(actionType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown actionType: " + actionType);
        }

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
            SkillTemplate skill = findOwnedSkill(actorId, skillName);
            if (skill == null) {
                throw new IllegalArgumentException("Unknown or unowned skill: " + skillName);
            }

            int cooldown = skillCooldowns.getOrDefault(cooldownKey(matchId, actorId, skill.name()), 0);
            if (cooldown > 0) {
                throw new IllegalArgumentException("Skill cooldown remaining: " + cooldown);
            }

            GameCharacter character = playerCharacters.get(actorId);
            int attackBonus = character == null ? 0 : character.attackBonus();
            int computedDamage = Math.max(5, Math.min(80, 10 + attackBonus + skill.baseDamage()));
            skillCooldowns.put(cooldownKey(matchId, actorId, skill.name()), skill.cooldownTurns());
            return new UseSkillAction(actorId, targetId, skill.name(), computedDamage);
        }

        if (type == ActionType.MOVE) {
            int targetCol = asInt(payload.get("targetCol"), -1);
            int targetRow = asInt(payload.get("targetRow"), -1);
            long requestedAtMs = System.currentTimeMillis();
            return new MoveAction(actorId, targetCol, targetRow, requestedAtMs);
        }

        return new EndTurnAction(actorId);
    }

    private void broadcastState(GameSession game, String requestId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("matchId", game.getMatchId());
        payload.put("turnPlayerId", game.getCurrentPlayerId());
        payload.put("windowIndex", game.getCurrentWindowIndex());
        payload.put("windowDurationSeconds", game.getWindowDurationSeconds());
        payload.put("readyPlayers", game.getReadyPlayers());
        payload.put("map", matchMaps.get(game.getMatchId()));
        payload.put("mapRows", game.getMapRows());
        payload.put("mapCols", game.getMapCols());

        List<Map<String, Object>> players = new ArrayList<>();
        game.snapshotPlayerStates().forEach((playerId, state) -> {
            Map<String, Object> p = new HashMap<>();
            p.put("playerId", playerId);
            p.put("hp", state.hp());
            p.put("maxHp", state.maxHp());
            p.put("defending", state.isDefending());
            p.put("alive", state.isAlive());
            p.put("strength", state.strength());
            p.put("agility", state.agility());
            p.put("intelligence", state.intelligence());
            p.put("energy", state.energy());
            p.put("maxEnergy", state.maxEnergy());
            game.getPlayerPosition(playerId).ifPresent(pos -> {
                p.put("mapCol", pos.col());
                p.put("mapRow", pos.row());
            });
            GameCharacter character = playerCharacters.get(playerId);
            if (character != null) {
                p.put("characterType", character.type().name());
                p.put("characterTitle", character.title());
                p.put("attackBonus", character.attackBonus());
            }
            p.put("skills", exportSkillStates(game.getMatchId(), playerId));
            players.add(p);
        });
        payload.put("players", players);

        broadcastToMatch(game.getMatchId(), new ResponseMessage("STATE_UPDATED", requestId, payload));
    }

    private void broadcastGameEnded(GameSession game, String requestId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("matchId", game.getMatchId());
        payload.put("winnerId", game.getWinnerId());
        payload.put("reason", "HP_ZERO");
        broadcastToMatch(game.getMatchId(), new ResponseMessage("GAME_ENDED", requestId, payload));
    }

    private void broadcastToMatch(String matchId, ResponseMessage response) {
        String encoded = MessageCodec.encodeResponse(response);
        playerToMatch.forEach((playerId, ownedMatchId) -> {
            if (!Objects.equals(matchId, ownedMatchId)) {
                return;
            }
            ClientSession client = clients.get(playerId);
            if (client != null) {
                client.out().println(encoded);
            }
        });
    }

    private ResponseMessage error(String requestId, String code, String message) {
        return new ResponseMessage("ERROR", requestId, Map.of("code", code, "message", message));
    }

    private void send(PrintWriter out, ResponseMessage response) {
        out.println(MessageCodec.encodeResponse(response));
    }

    private void scheduleTurnTimeout(String matchId) {
        // Window-based: advance only when every player presses ready.
        // Keep this method as a no-op for backward compatibility.
    }

    private void clearTimeout(String matchId) {
        ScheduledFuture<?> old = matchTimeouts.remove(matchId);
        if (old != null) {
            old.cancel(true);
        }
    }

    private void cleanupDisconnectedPlayer(String playerId) {
        if (playerId == null) {
            return;
        }
        clients.remove(playerId);
        waitingPlayers.remove(playerId);
        playerCharacterPrefs.remove(playerId);
        playerCharacters.remove(playerId);
        playerSkills.remove(playerId);
        String matchId = playerToMatch.remove(playerId);
        if (matchId != null) {
            clearTimeout(matchId);
            closeReplay(matchId);
            matchMaps.remove(matchId);
            clearSkillCooldownsForMatch(matchId);
        }
    }

    private void closeReplay(String matchId) {
        ReplayRecorder replay = replayRecorders.remove(matchId);
        if (replay != null) {
            replay.close();
        }
    }

    private String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private SkillTemplate findOwnedSkill(String playerId, String skillName) {
        return playerSkills.getOrDefault(playerId, List.of()).stream()
                .filter(skill -> skill.name().equalsIgnoreCase(skillName))
                .findFirst()
                .orElse(null);
    }

    private String cooldownKey(String matchId, String playerId, String skillName) {
        return matchId + ":" + playerId + ":" + skillName.toLowerCase(Locale.ROOT);
    }

    private void tickCooldowns(String matchId, String playerId) {
        for (SkillTemplate skill : playerSkills.getOrDefault(playerId, List.of())) {
            String key = cooldownKey(matchId, playerId, skill.name());
            int current = skillCooldowns.getOrDefault(key, 0);
            if (current > 0) {
                skillCooldowns.put(key, current - 1);
            }
        }
    }

    private List<Map<String, Object>> exportSkillStates(String matchId, String playerId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SkillTemplate skill : playerSkills.getOrDefault(playerId, List.of())) {
            String key = cooldownKey(matchId, playerId, skill.name());
            Map<String, Object> row = new HashMap<>();
            row.put("name", skill.name());
            row.put("bonusDamage", skill.baseDamage());
            row.put("cooldownTurns", skill.cooldownTurns());
            row.put("remainingCooldown", skillCooldowns.getOrDefault(key, 0));
            rows.add(row);
        }
        return rows;
    }

    private void clearSkillCooldownsForMatch(String matchId) {
        String prefix = matchId + ":";
        skillCooldowns.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
