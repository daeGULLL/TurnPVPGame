package com.turngame.engine.rules;

import java.util.Optional;

import com.turngame.domain.PlayerState;
import com.turngame.domain.defense.DefenseState;
import com.turngame.domain.map.MapCellPosition;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.GameSession;
import com.turngame.engine.command.AttackAction;
import com.turngame.engine.command.DefendAction;
import com.turngame.engine.command.EndTurnAction;
import com.turngame.engine.command.GameAction;
import com.turngame.engine.command.MoveAction;
import com.turngame.engine.command.UseSkillAction;

public class BasicRuleSet implements RuleSet {
    private static final int MIN_DAMAGE = 5;
    private static final int MAX_DAMAGE = 50;
    private static final int MAX_SKILL_DAMAGE = 80;
    private static final int ATTACK_ENERGY_COST = 1;
    private static final int DEFEND_ENERGY_COST = 1;
    private static final int MOVE_ENERGY_COST = 1;
    private static final int COLLISION_DAMAGE = 5;

    @Override
    public boolean validate(GameAction action, GameSession session) {
        if (!session.hasPlayer(action.actorId())) {
            return false;
        }

        if (session.isPlayerReady(action.actorId())) {
            return false;
        }

        boolean actorAlive = session.getPlayerState(action.actorId()).isAlive();
        if (!actorAlive) {
            return action instanceof EndTurnAction;
        }

        if (action instanceof AttackAction attack) {
            if (attack.damage() < MIN_DAMAGE || attack.damage() > MAX_DAMAGE) {
                return false;
            }
            if (!session.hasPlayer(attack.targetId()) || attack.actorId().equals(attack.targetId())) {
                return false;
            }

            PlayerState actor = session.getPlayerState(attack.actorId());
            return session.getPlayerState(attack.targetId()).isAlive()
                    && actor.hasEnergy(ATTACK_ENERGY_COST)
                    && actor.canSpendEnergyThisWindow(ATTACK_ENERGY_COST);
        }

        if (action instanceof UseSkillAction skill) {
            if (skill.damage() < MIN_DAMAGE || skill.damage() > MAX_SKILL_DAMAGE) {
                return false;
            }
            if (!session.hasPlayer(skill.targetId()) || skill.actorId().equals(skill.targetId())) {
                return false;
            }
            
            // 스킬 템플릿 조회
            Optional<SkillTemplate> skillTemplate = session.getSkillTemplate(skill.skillName());
            if (skillTemplate.isEmpty()) {
                return false;
            }

            Optional<MapCellPosition> actorPos = session.getPlayerPosition(skill.actorId());
            if (actorPos.isEmpty()) {
                return false;
            }

            if ((skill.targetCol() == null) != (skill.targetRow() == null)) {
                return false;
            }

            if (skill.targetCol() != null) {
                if (!session.isInsideMap(skill.targetCol(), skill.targetRow())) {
                    return false;
                }
                MapCellPosition aimedCell = new MapCellPosition(skill.targetCol(), skill.targetRow());
                if (!isWithinSkillRange(skillTemplate.get(), actorPos.get(), aimedCell)) {
                    return false;
                }
            }

            // 에너지 검사
            PlayerState actor = session.getPlayerState(skill.actorId());
            int requiredEnergy = skillTemplate.get().failEnergyCost();
            int maxPossibleEnergyCost = requiredEnergy + skillTemplate.get().successEnergyCost();
            if (!actor.hasEnergy(requiredEnergy)) {
                return false;
            }
            if (!actor.canSpendEnergyThisWindow(maxPossibleEnergyCost)) {
                return false;
            }

            return session.getPlayerState(skill.targetId()).isAlive();
        }

        if (action instanceof MoveAction move) {
            PlayerState actor = session.getPlayerState(move.actorId());
            if (actor == null || !actor.isAlive()) {
                return false;
            }
            if (session.isMoveLockedThisWindow(move.actorId()) || session.hasPendingSlowLanding(move.actorId())) {
                return false;
            }

            if (!actor.hasEnergy(MOVE_ENERGY_COST)) {
                return false;
            }
            if (!actor.canSpendEnergyThisWindow(MOVE_ENERGY_COST)) {
                return false;
            }

            Optional<MapCellPosition> origin = session.getPlayerPosition(move.actorId());
            if (origin.isEmpty()) {
                return false;
            }
            if (!session.isInsideMap(move.targetCol(), move.targetRow())) {
                return false;
            }
            if (!session.isPassableCell(move.targetCol(), move.targetRow())) {
                return false;
            }

            // 큐에 쌓인 이동들을 적용한 뒤의 위치에서 이번 이동을 검증한다.
            // 이동 "횟수"는 윈도우 에너지(reserveEnergyForQueuedAction)로 제한되며,
            // moveRange는 한 번의 이동이 갈 수 있는 최대 거리(기본 1칸)만 제한한다.
            MapCellPosition projected = projectedPositionAfterQueuedMoves(session, move.actorId(), origin.get());
            int requestedDistance = movementDistance(projected.col(), projected.row(), move.targetCol(), move.targetRow(), actor.diagonalMoveAllowed());
            if (requestedDistance <= 0) {
                return false;
            }
            return requestedDistance <= actor.moveRange();
        }

        return action instanceof DefendAction || action instanceof EndTurnAction;
    }

    @Override
    public void apply(GameAction action, GameSession session) {
        if (action instanceof AttackAction attack) {
            PlayerState actor = session.getPlayerState(attack.actorId());
            PlayerState target = session.getPlayerState(attack.targetId());
            if (areOrthogonallyAdjacent(session, attack.actorId(), attack.targetId())) {
                target.takeDamage(attack.damage());
            }
            actor.drainEnergy(ATTACK_ENERGY_COST);
            actor.clearDefense();
            target.clearDefense();
            return;
        }

        if (action instanceof DefendAction defend) {
            PlayerState actor = session.getPlayerState(defend.actorId());
            actor.drainEnergy(DEFEND_ENERGY_COST);
            actor.setDefense(
                    new DefenseState(defend.actorId(), defend.evadeSkillName(), defend.evadeStartTimeMs())
            );
            return;
        }

        if (action instanceof UseSkillAction skill) {
            PlayerState actor = session.getPlayerState(skill.actorId());
            PlayerState target = session.getPlayerState(skill.targetId());
            
            // 스킬 템플릿 조회
            Optional<SkillTemplate> skillTemplate = session.getSkillTemplate(skill.skillName());
            if (skillTemplate.isPresent()) {
                SkillTemplate template = skillTemplate.get();

                Optional<MapCellPosition> actorPos = session.getPlayerPosition(skill.actorId());
                Optional<MapCellPosition> targetPos = session.getPlayerPosition(skill.targetId());
                if (actorPos.isEmpty()) {
                    actor.drainEnergy(template.failEnergyCost());
                    actor.clearDefense();
                    target.clearDefense();
                    return;
                }

                MapCellPosition aimedCell = null;
                if (skill.targetCol() != null && skill.targetRow() != null) {
                    aimedCell = new MapCellPosition(skill.targetCol(), skill.targetRow());
                } else if (targetPos.isPresent()) {
                    aimedCell = targetPos.get();
                }
                if (aimedCell == null) {
                    actor.drainEnergy(template.failEnergyCost());
                    actor.clearDefense();
                    target.clearDefense();
                    return;
                }

                boolean inRange = isWithinSkillRange(template, actorPos.get(), aimedCell);
                boolean targetOnAimedCell = targetPos.isPresent()
                        && targetPos.get().col() == aimedCell.col()
                        && targetPos.get().row() == aimedCell.row();
                boolean requiresExactAimedTile = skill.targetCol() != null && skill.targetRow() != null;

                // 사거리 안에서만 실제 피해가 적용됨
                boolean success = inRange
                        && (!requiresExactAimedTile || targetOnAimedCell)
                        && SkillResolver.resolveSuccess(template, actor);
                if (success) {
                    int resolvedDamage = DamageResolver.calculateFinalDamage(template, skill.damage(), target, session);
                    target.takeDamage(resolvedDamage);
                }
                
                // 에너지 소모
                int energyCost = SkillResolver.calculateEnergyCost(template, success);
                actor.drainEnergy(energyCost);
                
                // 숙련도 경험치 획득
                SkillResolver.awardMasteryExperience(template, actor, success);
            } else {
                // 스킬 템플릿을 찾을 수 없으면 기본 에너지만 소모
                actor.drainEnergy(10);
            }
            
            actor.clearDefense();
            target.clearDefense();
            return;
        }

        if (action instanceof MoveAction move) {
            PlayerState actor = session.getPlayerState(move.actorId());
            if (actor == null) {
                return;
            }

            Optional<MapCellPosition> currentPos = session.getPlayerPosition(move.actorId());
            boolean validMove = currentPos.isPresent()
                    && session.isInsideMap(move.targetCol(), move.targetRow())
                    && session.isPassableCell(move.targetCol(), move.targetRow());

            if (validMove) {
                // 정산 시점에는 이동들이 순서대로 적용되므로, 현재 실제 위치 기준으로
                // 이번 한 번의 이동 거리만 moveRange와 비교한다.
                int requestedDistance = movementDistance(currentPos.get().col(), currentPos.get().row(), move.targetCol(), move.targetRow(), actor.diagonalMoveAllowed());
                validMove = requestedDistance > 0 && requestedDistance <= actor.moveRange();
            }

            if (!validMove) {
                actor.drainEnergy(MOVE_ENERGY_COST);
                actor.clearDefense();
                return;
            }

            Optional<String> occupantId = session.getPlayerIdAt(move.targetCol(), move.targetRow(), move.actorId());
            if (occupantId.isPresent()) {
                resolveCollisionMove(session, move.actorId(), occupantId.get(), move.targetCol(), move.targetRow(), move.requestedAtMs());
            } else {
                session.movePlayer(move.actorId(), move.targetCol(), move.targetRow(), move.requestedAtMs());
            }
            actor.drainEnergy(MOVE_ENERGY_COST);
            actor.clearDefense();
        }
    }

    private void resolveCollisionMove(GameSession session, String movingPlayerId, String collidedPlayerId, int targetCol, int targetRow, long movedAtMs) {
        PlayerState movingPlayer = session.getPlayerState(movingPlayerId);
        PlayerState collidedPlayer = session.getPlayerState(collidedPlayerId);
        if (movingPlayer == null || collidedPlayer == null) {
            return;
        }

        MapCellPosition movingPos = session.getPlayerPosition(movingPlayerId).orElse(null);
        MapCellPosition collidedPos = session.getPlayerPosition(collidedPlayerId).orElse(null);
        if (movingPos == null || collidedPos == null) {
            return;
        }

        int pushDirection = movingPos.col() <= targetCol ? -1 : 1;
        int oppositeDirection = -pushDirection;

        MapCellPosition movingPushPos = findHorizontalSlot(session, targetRow, targetCol, pushDirection, movingPos);
        MapCellPosition collidedPushPos = findHorizontalSlot(session, targetRow, targetCol, oppositeDirection, collidedPos);
        if (movingPushPos == null) {
            movingPushPos = movingPos;
        }
        if (collidedPushPos == null) {
            collidedPushPos = collidedPos;
        }

        movingPlayer.takeDamage(COLLISION_DAMAGE);
        collidedPlayer.takeDamage(COLLISION_DAMAGE);

        movingPlayer.clearDefense();
        collidedPlayer.clearDefense();

        session.movePlayer(movingPlayerId, movingPushPos.col(), movingPushPos.row(), movedAtMs);
        session.movePlayer(collidedPlayerId, collidedPushPos.col(), collidedPushPos.row(), movedAtMs);
    }

    private MapCellPosition findHorizontalSlot(GameSession session, int row, int targetCol, int direction, MapCellPosition fallback) {
        int step = direction < 0 ? -1 : 1;
        int startCol = targetCol + step;
        for (int col = startCol; session.isInsideMap(col, row); col += step) {
            if (session.isPassableCell(col, row) && !session.isCellOccupied(col, row, null)) {
                return new MapCellPosition(col, row);
            }
        }
        return fallback;
    }

    private boolean isWithinSkillRange(SkillTemplate skillTemplate, MapCellPosition actorPos, MapCellPosition targetPos) {
        if (skillTemplate == null || actorPos == null || targetPos == null) {
            return false;
        }

        SkillEffect effect = skillTemplate.effect();
        if (effect.areaType() == SkillEffect.AreaType.STATIC) {
            int deltaCol = targetPos.col() - actorPos.col();
            int deltaRow = targetPos.row() - actorPos.row();
            return effect.includesOffset(deltaCol, deltaRow);
        }

        int radius = Math.max(0, effect.areaRadius());
        return actorPos.chebyshevDistanceTo(targetPos) <= radius;
    }

    private boolean areOrthogonallyAdjacent(GameSession session, String actorId, String targetId) {
        Optional<MapCellPosition> actorPos = session.getPlayerPosition(actorId);
        Optional<MapCellPosition> targetPos = session.getPlayerPosition(targetId);
        return actorPos.isPresent()
                && targetPos.isPresent()
                && actorPos.get().manhattanDistanceTo(targetPos.get()) == 1;
    }

    private MapCellPosition projectedPositionAfterQueuedMoves(GameSession session, String actorId, MapCellPosition origin) {
        int col = origin.col();
        int row = origin.row();
        for (GameAction queued : session.getPendingActions(actorId)) {
            if (queued instanceof MoveAction queuedMove) {
                col = queuedMove.targetCol();
                row = queuedMove.targetRow();
            }
        }
        return new MapCellPosition(col, row);
    }

    private int movementDistance(int fromCol, int fromRow, int toCol, int toRow, boolean diagonalAllowed) {
        int deltaCol = Math.abs(fromCol - toCol);
        int deltaRow = Math.abs(fromRow - toRow);
        if (diagonalAllowed) {
            return Math.max(deltaCol, deltaRow);
        }
        return deltaCol + deltaRow;
    }

    @Override
    public Optional<String> checkWin(GameSession session) {
        return session.getAllPlayerIds().stream()
                .filter(playerId -> session.getPlayerState(playerId).hp() > 0)
                .findFirst()
                .filter(winner -> session.getAllPlayerIds().stream()
                        .filter(playerId -> session.getPlayerState(playerId).hp() > 0)
                        .count() == 1);
    }
}
