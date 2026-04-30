package com.turngame.engine.command;

import com.turngame.domain.enums.ActionType;

public interface GameAction {
    ActionType actionType();
    String actorId();
}
