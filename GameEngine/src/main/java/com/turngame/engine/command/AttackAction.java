package com.turngame.engine.command;

import com.turngame.domain.enums.ActionType;

public record AttackAction(String actorId, String targetId, int damage) implements GameAction {
    @Override
    public ActionType actionType() {
        return ActionType.ATTACK;
    }
}
