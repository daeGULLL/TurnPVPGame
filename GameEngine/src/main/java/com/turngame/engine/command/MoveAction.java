package com.turngame.engine.command;

import com.turngame.domain.enums.ActionType;

public record MoveAction(String actorId, int targetCol, int targetRow, long requestedAtMs) implements GameAction {
    @Override
    public ActionType actionType() {
        return ActionType.MOVE;
    }
}
