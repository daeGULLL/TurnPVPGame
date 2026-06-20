package com.turngame.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.turngame.domain.PlayerState;
import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.ActionType;
import com.turngame.domain.map.BattleMap;
import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.command.AttackAction;
import com.turngame.engine.command.DefendAction;
import com.turngame.engine.command.EndTurnAction;
import com.turngame.engine.command.GameAction;
import com.turngame.engine.command.MoveAction;
import com.turngame.engine.command.UseSkillAction;
import com.turngame.engine.rules.RuleSet;
import com.turngame.event.ActionAppliedEvent;
import com.turngame.event.EventBus;
import com.turngame.event.GameEndedEvent;
import com.turngame.event.TurnStartedEvent;

public class GameSession {
    private final String matchId;
    private final RuleSet ruleSet;
    private final TurnManager turnManager;
    private final EventBus eventBus;
    private final BattleMap battleMap;
    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<String, MapCellPosition> playerPositions = new ConcurrentHashMap<>();
    private final Map<String, Integer> movementLockedUntilWindow = new ConcurrentHashMap<>();
    private final Map<String, SkillTemplate> skillTemplates = new ConcurrentHashMap<>();
    private final Map<String, List<GameAction>> pendingActions = new ConcurrentHashMap<>();
    private final List<ResolutionStep> lastResolutionSteps = new ArrayList<>();
    private volatile boolean windowAdvanced;
    private volatile boolean finished;
    private volatile String winnerId;
    private volatile int lastResolvedWindowIndex;

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
        this.lastResolvedWindowIndex = -1;
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
        if (battleMap.isSlowCell(targetCol, targetRow)) {
            movementLockedUntilWindow.put(playerId, turnManager.currentWindowIndex() + 1);
        }
        PlayerState state = getPlayerState(playerId);
        if (state != null) {
            state.markMoved(movedAtMs);
        }
    }

    public boolean isPassableCell(int col, int row) {
        return battleMap.isPassableCell(col, row);
    }

    public boolean isSlowCell(int col, int row) {
        return battleMap.isSlowCell(col, row);
    }

    public char getTileAt(int col, int row) {
        return battleMap.tileAt(col, row);
    }

    public synchronized boolean isMoveLockedThisWindow(String playerId) {
        return movementLockedUntilWindow.getOrDefault(playerId, -1) >= turnManager.currentWindowIndex();
    }

    public synchronized boolean hasPendingSlowLanding(String playerId) {
        List<GameAction> actions = pendingActions.getOrDefault(playerId, List.of());
        for (int i = actions.size() - 1; i >= 0; i--) {
            GameAction action = actions.get(i);
            if (action instanceof MoveAction move) {
                return battleMap.isSlowCell(move.targetCol(), move.targetRow());
            }
        }
        return false;
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
        action = Objects.requireNonNull(action, "action");
        String actorId = Objects.requireNonNull(action.actorId(), "actorId");

        if (finished) {
            throw new IllegalStateException("Game already finished");
        }

        if (!ruleSet.validate(action, this)) {
            throw new IllegalArgumentException("Invalid action");
        }

        // EndTurnAction 외의 액션은 큐에 저장
        if (!(action instanceof EndTurnAction)) {
            reserveEnergyForQueuedAction(action);
            pendingActions.computeIfAbsent(actorId, k -> new ArrayList<>()).add(action);
            return;
        }

        // EndTurnAction: ready 표시
        if (turnManager.markReady(actorId) && turnManager.allReady()) {
            // 양쪽 모두 ready → 모든 pending 액션 적용
            applyAllPendingActions();
            lastResolvedWindowIndex = turnManager.currentWindowIndex();

            // 이후 승패 판정
            ruleSet.checkWin(this).ifPresent(winningPlayerId -> {
                finished = true;
                this.winnerId = winningPlayerId;
                eventBus.publish(new GameEndedEvent(matchId, winningPlayerId));
            });

            if (!finished) {
                // 다음 턴 준비
                playerStates.values().forEach(PlayerState::resetEnergySpentInWindow);
                turnManager.nextTurn();
                windowAdvanced = true;
                pendingActions.clear();
                eventBus.publish(new TurnStartedEvent(matchId, "WINDOW-" + turnManager.currentWindowIndex()));
            }
        }
    }

    private void applyAllPendingActions() {
        List<String> order = turnManager.turnOrder();
        int maxActionCount = 0;
        for (String playerId : order) {
            maxActionCount = Math.max(maxActionCount, pendingActions.getOrDefault(playerId, List.of()).size());
        }

        List<ResolutionStep> resolvedSteps = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < maxActionCount; stepIndex++) {
            Map<String, Integer> hpBefore = snapshotHpValues();
            Map<String, MapCellPosition> positionBefore = snapshotPlayerPositions();
            List<ResolvedActionView> resolvedActions = new ArrayList<>();
            for (String playerId : order) {
                List<GameAction> actions = pendingActions.getOrDefault(playerId, List.of());
                if (stepIndex >= actions.size()) {
                    continue;
                }
                GameAction action = actions.get(stepIndex);
                ruleSet.apply(action, this);
                eventBus.publish(new ActionAppliedEvent(matchId, action));
                resolvedActions.add(ResolvedActionView.from(action));
            }

            if (!resolvedActions.isEmpty()) {
                resolvedSteps.add(new ResolutionStep(
                        stepIndex + 1,
                        List.copyOf(resolvedActions),
                        hpBefore,
                        snapshotHpValues(),
                        positionBefore,
                        snapshotPlayerPositions()
                ));
            }
        }

        synchronized (this) {
            lastResolutionSteps.clear();
            lastResolutionSteps.addAll(resolvedSteps);
        }
    }

    private Map<String, Integer> snapshotHpValues() {
        Map<String, Integer> hp = new HashMap<>();
        for (Map.Entry<String, PlayerState> entry : playerStates.entrySet()) {
            hp.put(entry.getKey(), entry.getValue().hp());
        }
        return Collections.unmodifiableMap(hp);
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

    public synchronized List<GameAction> getPendingActions(String playerId) {
        return List.copyOf(pendingActions.getOrDefault(playerId, List.of()));
    }

    public synchronized List<ResolutionStep> snapshotLastResolutionSteps() {
        return List.copyOf(lastResolutionSteps);
    }

    public int getLastResolvedWindowIndex() {
        return lastResolvedWindowIndex;
    }

    public synchronized Optional<MapCellPosition> getProjectedPlayerPosition(String playerId) {
        MapCellPosition current = playerPositions.get(playerId);
        if (current == null) {
            return Optional.empty();
        }

        int projectedCol = current.col();
        int projectedRow = current.row();
        for (GameAction action : pendingActions.getOrDefault(playerId, List.of())) {
            if (action instanceof MoveAction move) {
                projectedCol = move.targetCol();
                projectedRow = move.targetRow();
            }
        }
        return Optional.of(new MapCellPosition(projectedCol, projectedRow));
    }

    public synchronized int getPendingEnergySpent(String playerId) {
        int total = 0;
        for (GameAction action : pendingActions.getOrDefault(playerId, List.of())) {
            if (action instanceof MoveAction || action instanceof AttackAction || action instanceof DefendAction) {
                total += 1;
            } else if (action instanceof UseSkillAction skill) {
                SkillTemplate template = skillTemplates.get(skill.skillName());
                if (template != null) {
                    total += template.failEnergyCost() + template.successEnergyCost();
                }
            }
        }
        return total;
    }

    private void reserveEnergyForQueuedAction(GameAction action) {
        PlayerState actor = playerStates.get(action.actorId());
        if (actor == null) {
            return;
        }

        int reservedEnergy = 0;
        if (action instanceof AttackAction) {
            reservedEnergy = 1;
        } else if (action instanceof DefendAction) {
            reservedEnergy = 1;
        } else if (action instanceof MoveAction) {
            reservedEnergy = 1;
        } else if (action instanceof UseSkillAction skill) {
            SkillTemplate template = skillTemplates.get(skill.skillName());
            if (template != null) {
                reservedEnergy = template.failEnergyCost() + template.successEnergyCost();
            } else {
                reservedEnergy = 10;
            }
        }

        if (reservedEnergy > 0) {
            actor.recordEnergySpentInWindow(reservedEnergy);
        }
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
        if (playerPositions.isEmpty()) {
            spawn = preferredOrFirstAvailable(0, battleMap.rows() - 1);
        } else if (playerPositions.size() == 1) {
            spawn = preferredOrFirstAvailable(battleMap.cols() - 1, 0);
        } else {
            spawn = firstAvailableCell();
        }
        playerPositions.put(playerId, spawn);
    }

    private MapCellPosition preferredOrFirstAvailable(int preferredCol, int preferredRow) {
        if (isInsideMap(preferredCol, preferredRow)
                && isPassableCell(preferredCol, preferredRow)
                && !isCellOccupied(preferredCol, preferredRow, null)) {
            return new MapCellPosition(preferredCol, preferredRow);
        }
        return firstAvailableCell();
    }

    private MapCellPosition firstAvailableCell() {
        for (int row = 0; row < battleMap.rows(); row++) {
            for (int col = 0; col < battleMap.cols(); col++) {
                if (isPassableCell(col, row) && !isCellOccupied(col, row, null)) {
                    return new MapCellPosition(col, row);
                }
            }
        }
        return new MapCellPosition(0, 0);
    }

    public record ResolutionStep(
            int stepIndex,
            List<ResolvedActionView> actions,
            Map<String, Integer> hpBeforeByPlayer,
            Map<String, Integer> hpAfterByPlayer,
            Map<String, MapCellPosition> positionBeforeByPlayer,
            Map<String, MapCellPosition> positionAfterByPlayer
    ) {
    }

    public record ResolvedActionView(
            String actorId,
            ActionType actionType,
            String targetId,
            String skillName,
            Integer targetCol,
            Integer targetRow,
            Integer damage
    ) {
        public static ResolvedActionView from(GameAction action) {
            action = Objects.requireNonNull(action, "action");
            String actorId = Objects.requireNonNull(action.actorId(), "actorId");

            if (action instanceof AttackAction attack) {
                return new ResolvedActionView(
                        attack.actorId(),
                        ActionType.ATTACK,
                        attack.targetId(),
                        null,
                        null,
                        null,
                        attack.damage()
                );
            }
            if (action instanceof UseSkillAction skill) {
                return new ResolvedActionView(
                        skill.actorId(),
                        ActionType.USE_SKILL,
                        skill.targetId(),
                        skill.skillName(),
                        skill.targetCol(),
                        skill.targetRow(),
                        skill.damage()
                );
            }
            if (action instanceof MoveAction move) {
                return new ResolvedActionView(
                        move.actorId(),
                        ActionType.MOVE,
                        null,
                        null,
                        move.targetCol(),
                        move.targetRow(),
                        null
                );
            }
            if (action instanceof DefendAction) {
                return new ResolvedActionView(
                    actorId,
                        ActionType.DEFEND,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }

            return new ResolvedActionView(
                    actorId,
                    action.actionType(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}
