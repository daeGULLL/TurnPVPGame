package com.turngame.engine;

import com.turngame.domain.PlayerState;
import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.command.EndTurnAction;
import com.turngame.engine.command.GameAction;
import com.turngame.engine.rules.RuleSet;
import com.turngame.event.ActionAppliedEvent;
import com.turngame.event.EventBus;
import com.turngame.event.GameEndedEvent;
import com.turngame.event.TurnStartedEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GameSession {
    private final String matchId;
    private final RuleSet ruleSet;
    private final TurnManager turnManager;
    private final EventBus eventBus;
    private final BattleMap battleMap;
    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<String, MapCellPosition> playerPositions = new ConcurrentHashMap<>();
    private final Map<String, SkillTemplate> skillTemplates = new ConcurrentHashMap<>();
    private volatile boolean windowAdvanced;
    private volatile boolean finished;
    private volatile String winnerId;

    public GameSession(String matchId, RuleSet ruleSet, TurnManager turnManager, EventBus eventBus) {
        this(matchId, ruleSet, turnManager, eventBus, new BattleMap("default", "Default", "Default battlefield"));
    }

    public GameSession(String matchId, RuleSet ruleSet, TurnManager turnManager, EventBus eventBus, BattleMap battleMap) {
        this.matchId = matchId;
        this.ruleSet = ruleSet;
        this.turnManager = turnManager;
        this.eventBus = eventBus;
        this.battleMap = battleMap;
        this.windowAdvanced = false;
        this.finished = false;
        this.winnerId = null;
    }

    public void addPlayer(String playerId, int hp) {
        playerStates.put(playerId, new PlayerState(hp));
        assignInitialPosition(playerId);
    }

    public void addPlayer(String playerId, GameCharacter character) {
        playerStates.put(
                playerId,
                PlayerState.fromCoreStats(character.strength(), character.agility(), character.intelligence(), 100)
        );
        assignInitialPosition(playerId);
    }

    public void addPlayer(String playerId, PlayerState state) {
        playerStates.put(playerId, state);
        assignInitialPosition(playerId);
    }

    /**
     * 게임에 사용될 스킬 템플릿들을 등록합니다.
     */
    public void registerSkill(SkillTemplate skill) {
        skillTemplates.put(skill.name(), skill);
    }

    /**
     * 스킬 이름으로 스킬 템플릿을 조회합니다.
     */
    public Optional<SkillTemplate> getSkillTemplate(String skillName) {
        return Optional.ofNullable(skillTemplates.get(skillName));
    }

    public int getMapRows() {
        return battleMap.rows();
    }

    public int getMapCols() {
        return battleMap.cols();
    }

    public BattleMap getBattleMap() {
        return battleMap;
    }

    public Optional<MapCellPosition> getPlayerPosition(String playerId) {
        return Optional.ofNullable(playerPositions.get(playerId));
    }

    public Map<String, MapCellPosition> snapshotPlayerPositions() {
        return Collections.unmodifiableMap(new HashMap<>(playerPositions));
    }

    public boolean isInsideMap(int col, int row) {
        return col >= 0 && col < battleMap.cols() && row >= 0 && row < battleMap.rows();
    }

    public boolean isCellOccupied(int col, int row, String exceptPlayerId) {
        for (Map.Entry<String, MapCellPosition> entry : playerPositions.entrySet()) {
            if (entry.getKey().equals(exceptPlayerId)) {
                continue;
            }
            MapCellPosition pos = entry.getValue();
            if (pos.col() == col && pos.row() == row) {
                return true;
            }
        }
        return false;
    }

    public Optional<String> getPlayerIdAt(int col, int row, String exceptPlayerId) {
        for (Map.Entry<String, MapCellPosition> entry : playerPositions.entrySet()) {
            if (entry.getKey().equals(exceptPlayerId)) {
                continue;
            }
            MapCellPosition pos = entry.getValue();
            if (pos.col() == col && pos.row() == row) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public void movePlayer(String playerId, int targetCol, int targetRow, long movedAtMs) {
        if (!isInsideMap(targetCol, targetRow)) {
            throw new IllegalArgumentException("Target cell is outside map bounds");
        }
        playerPositions.put(playerId, new MapCellPosition(targetCol, targetRow));
        PlayerState state = getPlayerState(playerId);
        if (state != null) {
            state.markMoved(movedAtMs);
        }
    }

    public List<MapCellPosition> calculateAffectedCells(MapCellPosition center, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }

        List<MapCellPosition> affected = new java.util.ArrayList<>();
        for (int row = 0; row < battleMap.rows(); row++) {
            for (int col = 0; col < battleMap.cols(); col++) {
                MapCellPosition cell = new MapCellPosition(col, row);
                if (center.manhattanDistanceTo(cell) <= radius) {
                    affected.add(cell);
                }
            }
        }
        return affected;
    }

    public synchronized void submitAction(GameAction action) {
        if (finished) {
            throw new IllegalStateException("Game already finished");
        }

        if (!ruleSet.validate(action, this)) {
            throw new IllegalArgumentException("Invalid action");
        }

        ruleSet.apply(action, this);
        eventBus.publish(new ActionAppliedEvent(matchId, action));

        ruleSet.checkWin(this).ifPresent(winnerId -> {
            finished = true;
            this.winnerId = winnerId;
            eventBus.publish(new GameEndedEvent(matchId, winnerId));
        });

        if (!finished && action.actionType() == ActionType.END_TURN && action instanceof EndTurnAction) {
            if (turnManager.markReady(action.actorId()) && turnManager.allReady()) {
                playerStates.values().forEach(PlayerState::resetEnergySpentInWindow);
                turnManager.nextTurn();
                windowAdvanced = true;
                eventBus.publish(new TurnStartedEvent(matchId, "WINDOW-" + turnManager.currentWindowIndex()));
            }
        }
    }

    public String getCurrentPlayerId() {
        return turnManager.currentPlayerId();
    }

    public int getCurrentWindowIndex() {
        return turnManager.currentWindowIndex();
    }

    public int getWindowDurationSeconds() {
        return turnManager.windowDurationSeconds();
    }

    public boolean isPlayerReady(String playerId) {
        return turnManager.isReady(playerId);
    }

    public Set<String> getReadyPlayers() {
        return turnManager.readyPlayers();
    }

    public synchronized boolean consumeWindowAdvancedFlag() {
        boolean v = windowAdvanced;
        windowAdvanced = false;
        return v;
    }

    public String getMatchId() {
        return matchId;
    }

    public boolean isFinished() {
        return finished;
    }

    public String getWinnerId() {
        return winnerId;
    }

    public boolean hasPlayer(String playerId) {
        return playerStates.containsKey(playerId);
    }

    public PlayerState getPlayerState(String playerId) {
        return playerStates.get(playerId);
    }

    public Set<String> getAllPlayerIds() {
        return Collections.unmodifiableSet(playerStates.keySet());
    }

    public Map<String, PlayerState> snapshotPlayerStates() {
        return Collections.unmodifiableMap(playerStates);
    }

    private void assignInitialPosition(String playerId) {
        MapCellPosition spawn;
        if ("p-1".equals(playerId)) {
            spawn = new MapCellPosition(0, battleMap.rows() - 1);
        } else if ("p-2".equals(playerId)) {
            spawn = new MapCellPosition(battleMap.cols() - 1, 0);
        } else {
            spawn = firstAvailableCell();
        }
        playerPositions.put(playerId, spawn);
    }

    private MapCellPosition firstAvailableCell() {
        for (int row = 0; row < battleMap.rows(); row++) {
            for (int col = 0; col < battleMap.cols(); col++) {
                if (!isCellOccupied(col, row, null)) {
                    return new MapCellPosition(col, row);
                }
            }
        }
        return new MapCellPosition(0, 0);
    }
}
