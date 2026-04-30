package com.turngame.engine.command;

import com.turngame.domain.enums.ActionType;

public record EndTurnAction(String actorId) implements GameAction {
    @Override
    public ActionType actionType() {
        return ActionType.END_TURN;
    }
}
