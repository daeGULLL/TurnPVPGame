package com.magefight.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.magefight.content.factory.GamePresetFactory;
import com.turngame.domain.PlayerState;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.GameSession;
import com.turngame.engine.TurnManager;
import com.turngame.engine.rules.BasicRuleSet;
import com.turngame.event.EventBus;

final class OnlineStateSyncService {
    private static final String PLAYER_ALIAS = "p-1";
    private static final String OPPONENT_ALIAS = "p-2";

    private final GamePresetFactory presetFactory;

    OnlineStateSyncService(GamePresetFactory presetFactory) {
        this.presetFactory = presetFactory;
    }

    record SyncSnapshot(
            GameSession session,
            BattleMap map,
            String opponentId,
            List<String> mySkillNames,
            String myCharacterDisplayName,
            String opponentCharacterDisplayName,
            int resolvedWindowIndex,
            List<GameSession.ResolutionStep> resolutionSteps,
            String turnPlayerId,
            String opponentSkinColorHex,
            String opponentOutfitColorHex,
            Map<String, Integer> myCooldowns
    ) {}

    @SuppressWarnings("unchecked")
    SyncSnapshot synchronize(
            Map<String, Object> payload,
            String myPlayerId,
            String knownOpponentId,
            BattleMap currentMap
    ) {
        if (payload == null || myPlayerId == null || myPlayerId.isBlank()) {
            return null;
        }

        Object playersObj = payload.get("players");
        if (!(playersObj instanceof List<?> players)) {
            return null;
        }

        Map<String, Object> me = null;
        Map<String, Object> opponent = null;
        String opponentId = knownOpponentId;

        for (Object p : players) {
            if (!(p instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> playerData = (Map<String, Object>) rawMap;
            String pid = String.valueOf(playerData.get("playerId"));
            if (myPlayerId.equals(pid)) {
                me = playerData;
            } else {
                opponent = playerData;
                opponentId = pid;
            }
        }

        if (me == null || opponent == null || opponentId == null || opponentId.isBlank()) {
            System.out.println("[OnlineStateSync] sync skipped: me=" + (me != null)
                    + ", opponent=" + (opponent != null) + ", myPlayerId=" + myPlayerId
                    + ", playersInPayload=" + players.size());
            return null;
        }

        BattleMap syncedMap = resolveMap(payload, currentMap);
        EventBus eventBus = new EventBus();
        GameSession snapshot = new GameSession(
                "magefight-online",
                new BasicRuleSet(),
                new TurnManager(List.of(PLAYER_ALIAS, OPPONENT_ALIAS)),
                eventBus,
                syncedMap
        );

        snapshot.addPlayer(PLAYER_ALIAS, toPlayerState(me));
        snapshot.addPlayer(OPPONENT_ALIAS, toPlayerState(opponent));
        applyPosition(snapshot, PLAYER_ALIAS, me);
        applyPosition(snapshot, OPPONENT_ALIAS, opponent);
        registerSkills(snapshot, me);
        registerSkills(snapshot, opponent);

        List<String> mySkills = extractSkillNames(me);
        String myCharacterDisplayName = preferredName(me, myPlayerId);
        String opponentCharacterDisplayName = preferredName(opponent, opponentId);
        int resolvedWindowIndex = asInt(payload.get("resolvedWindowIndex"), -1);
        List<GameSession.ResolutionStep> steps = parseResolutionSteps(payload, myPlayerId, opponentId);
        String turnPlayerId = String.valueOf(payload.get("turnPlayerId"));
        String opponentSkinColorHex = asHexColor(opponent.get("skinColorHex"));
        String opponentOutfitColorHex = asHexColor(opponent.get("outfitColorHex"));
        Map<String, Integer> myCooldowns = parseCooldowns(me.get("skillCooldowns"));

        return new SyncSnapshot(
                snapshot,
                syncedMap,
                opponentId,
                mySkills,
                myCharacterDisplayName,
                opponentCharacterDisplayName,
                resolvedWindowIndex,
                steps,
                turnPlayerId,
                opponentSkinColorHex,
                opponentOutfitColorHex,
                myCooldowns
        );
    }

    private String preferredName(Map<String, Object> playerData, String fallback) {
        if (playerData == null) {
            return fallback;
        }
        String characterDisplayName = String.valueOf(playerData.getOrDefault("characterDisplayName", "")).trim();
        if (!characterDisplayName.isBlank() && !"null".equalsIgnoreCase(characterDisplayName)) {
            return characterDisplayName;
        }
        String nickname = String.valueOf(playerData.getOrDefault("nickname", "")).trim();
        if (!nickname.isBlank() && !"null".equalsIgnoreCase(nickname)) {
            return nickname;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private BattleMap resolveMap(Map<String, Object> payload, BattleMap currentMap) {
        Object mapObj = payload.get("map");
        if (mapObj instanceof String mapName) {
            return presetFactory.createMap(mapName);
        }
        if (!(mapObj instanceof Map<?, ?> rawMap)) {
            return currentMap;
        }

        Map<String, Object> mapData = (Map<String, Object>) rawMap;
        String code = String.valueOf(mapData.getOrDefault("code", "online"));
        String name = String.valueOf(mapData.getOrDefault("name", "Online Map"));
        String description = String.valueOf(mapData.getOrDefault("description", "Synced battlefield"));
        int rows = asInt(mapData.get("rows"), 0);
        int cols = asInt(mapData.get("cols"), 0);
        List<String> layout = toStringList(mapData.get("layoutRows"));
        if (rows > 0 && cols > 0 && !layout.isEmpty()) {
            return new BattleMap(code, name, description, rows, cols, layout);
        }
        return currentMap;
    }

    private PlayerState toPlayerState(Map<String, Object> playerData) {
        int hp = asInt(playerData.get("hp"), 1);
        int maxHp = Math.max(1, asInt(playerData.get("maxHp"), hp));
        int maxEnergy = Math.max(1, asInt(playerData.get("maxEnergy"), 100));
        PlayerState state = new PlayerState(maxHp, maxEnergy);
        if (hp < maxHp) {
            state.takeDamage(maxHp - hp);
        }
        int maxEnergySpendPerWindow = asInt(
                playerData.get("maxEnergySpendPerWindow"),
                asInt(playerData.get("turnEnergyCap"), 6)
        );
        if (maxEnergySpendPerWindow <= 0 || maxEnergySpendPerWindow == Integer.MAX_VALUE) {
            maxEnergySpendPerWindow = 6;
        }
        state.setMaxEnergySpendPerWindow(maxEnergySpendPerWindow);
        int spentInWindow = Math.max(0, asInt(playerData.get("energySpentInWindow"), 0));
        if (spentInWindow > 0) {
            state.recordEnergySpentInWindow(spentInWindow);
        }
        return state;
    }

    private void applyPosition(GameSession snapshot, String aliasId, Map<String, Object> playerData) {
        int col = asInt(playerData.get("mapCol"), -1);
        int row = asInt(playerData.get("mapRow"), -1);
        if (col < 0 || row < 0 || !snapshot.isInsideMap(col, row)) {
            return;
        }
        snapshot.movePlayer(aliasId, col, row, System.currentTimeMillis());
    }

    private void registerSkills(GameSession snapshot, Map<String, Object> playerData) {
        Object skillsObj = playerData.get("skills");
        if (!(skillsObj instanceof List<?> skills)) {
            return;
        }
        for (Object skillObj : skills) {
            SkillTemplate template = parseSkillTemplate(skillObj);
            if (template != null) {
                snapshot.registerSkill(template);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private SkillTemplate parseSkillTemplate(Object skillObj) {
        if (!(skillObj instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Map<String, Object> skillData = (Map<String, Object>) rawMap;
        String name = String.valueOf(skillData.getOrDefault("name", "UnknownSkill"));
        int baseDamage = asInt(skillData.get("baseDamage"), 0);
        int cooldownTurns = asInt(skillData.get("cooldownTurns"), 0);
        double baseSuccessProbability = asDouble(skillData.get("baseSuccessProbability"), 1.0);
        int failEnergyCost = asInt(skillData.get("failEnergyCost"), 0);
        int successEnergyCost = asInt(skillData.get("successEnergyCost"), 0);
        int prepareCastMs = asInt(skillData.get("prepareCastMs"), 0);
        boolean isDefenseSkill = asBoolean(skillData.get("isDefenseSkill"));
        int evadeDurationMs = asInt(skillData.get("evadeDurationMs"), 0);

        SkillEffect effect = new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0);
        Object effectObj = skillData.get("effect");
        if (effectObj instanceof Map<?, ?> rawEffect) {
            Map<String, Object> effectData = (Map<String, Object>) rawEffect;
            String areaTypeName = String.valueOf(effectData.getOrDefault("areaType", "STATIC"));
            SkillEffect.AreaType areaType;
            try {
                areaType = SkillEffect.AreaType.valueOf(areaTypeName);
            } catch (IllegalArgumentException ex) {
                areaType = SkillEffect.AreaType.STATIC;
            }
                List<String> areaPatternRows = toStringList(effectData.get("areaPatternRows"));
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

    @SuppressWarnings("unchecked")
    private List<String> extractSkillNames(Map<String, Object> playerData) {
        Object skillsObj = playerData.get("skills");
        if (!(skillsObj instanceof List<?> skills)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object skillObj : skills) {
            if (!(skillObj instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> skillData = (Map<String, Object>) rawMap;
            String skillName = String.valueOf(skillData.getOrDefault("name", "")).trim();
            if (!skillName.isBlank()) {
                names.add(skillName);
            }
        }
        return List.copyOf(names);
    }

    @SuppressWarnings("unchecked")
    private List<GameSession.ResolutionStep> parseResolutionSteps(Map<String, Object> payload, String myId, String oppId) {
        Object stepsObj = payload.get("resolutionSteps");
        if (!(stepsObj instanceof List<?> stepsObjList)) {
            return List.of();
        }
        List<GameSession.ResolutionStep> steps = new ArrayList<>();
        for (Object stepObj : stepsObjList) {
            if (!(stepObj instanceof Map<?, ?> rawStep)) {
                continue;
            }
            Map<String, Object> stepData = (Map<String, Object>) rawStep;
            int stepIndex = asInt(stepData.get("stepIndex"), 0);
            List<GameSession.ResolvedActionView> actions = parseStepActions(stepData.get("actions"), myId, oppId);
            Map<String, Integer> hpBefore = parseHpMap(stepData.get("hpBeforeByPlayer"), myId, oppId);
            Map<String, Integer> hpAfter = parseHpMap(stepData.get("hpAfterByPlayer"), myId, oppId);
            Map<String, MapCellPosition> posBefore = parsePosMap(stepData.get("positionBeforeByPlayer"), myId, oppId);
            Map<String, MapCellPosition> posAfter = parsePosMap(stepData.get("positionAfterByPlayer"), myId, oppId);
            steps.add(new GameSession.ResolutionStep(stepIndex, actions, hpBefore, hpAfter, posBefore, posAfter));
        }
        return List.copyOf(steps);
    }

    @SuppressWarnings("unchecked")
    private List<GameSession.ResolvedActionView> parseStepActions(Object actionsObj, String myId, String oppId) {
        if (!(actionsObj instanceof List<?> actionsList)) {
            return List.of();
        }
        List<GameSession.ResolvedActionView> actions = new ArrayList<>();
        for (Object actionObj : actionsList) {
            if (!(actionObj instanceof Map<?, ?> rawAction)) {
                continue;
            }
            Map<String, Object> actionData = (Map<String, Object>) rawAction;
            ActionType type;
            try {
                type = ActionType.valueOf(String.valueOf(actionData.get("actionType")));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            actions.add(new GameSession.ResolvedActionView(
                    aliasPlayerId(String.valueOf(actionData.get("actorId")), myId, oppId),
                    type,
                    actionData.get("targetId") == null ? null : aliasPlayerId(String.valueOf(actionData.get("targetId")), myId, oppId),
                    actionData.get("skillName") == null ? null : String.valueOf(actionData.get("skillName")),
                    actionData.get("targetCol") == null ? null : asInt(actionData.get("targetCol"), 0),
                    actionData.get("targetRow") == null ? null : asInt(actionData.get("targetRow"), 0),
                    actionData.get("damage") == null ? null : asInt(actionData.get("damage"), 0)
            ));
        }
        return List.copyOf(actions);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> parseHpMap(Object hpObj, String myId, String oppId) {
        if (!(hpObj instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Integer> parsed = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String alias = aliasPlayerId(String.valueOf(entry.getKey()), myId, oppId);
            parsed.put(alias, asInt(entry.getValue(), 0));
        }
        return Map.copyOf(parsed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, MapCellPosition> parsePosMap(Object posObj, String myId, String oppId) {
        if (!(posObj instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, MapCellPosition> parsed = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawPos)) {
                continue;
            }
            Map<String, Object> posData = (Map<String, Object>) rawPos;
            String alias = aliasPlayerId(String.valueOf(entry.getKey()), myId, oppId);
            parsed.put(alias, new MapCellPosition(
                    asInt(posData.get("col"), 0),
                    asInt(posData.get("row"), 0)
            ));
        }
        return Map.copyOf(parsed);
    }

    private String aliasPlayerId(String serverId, String myId, String oppId) {
        if (serverId == null) {
            return null;
        }
        if (serverId.equals(myId)) {
            return PLAYER_ALIAS;
        }
        if (serverId.equals(oppId)) {
            return OPPONENT_ALIAS;
        }
        return serverId;
    }

    private int asInt(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number num) return num.intValue();
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String asHexColor(Object obj) {
        if (obj == null) return null;
        String hex = String.valueOf(obj).trim();
        return hex.matches("^#[0-9a-fA-F]{6}$") ? hex : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> parseCooldowns(Object obj) {
        if (!(obj instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) rawMap).entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey(), asInt(entry.getValue(), 0));
            }
        }
        return result;
    }

    private double asDouble(Object obj, double defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number num) return num.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(obj));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean asBoolean(Object obj) {
        if (obj instanceof Boolean b) {
            return b;
        }
        return obj != null && Boolean.parseBoolean(String.valueOf(obj));
    }

    private List<String> toStringList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            values.add(String.valueOf(item));
        }
        return List.copyOf(values);
    }
}
