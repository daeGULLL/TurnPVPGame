package com.turngame.engine.command;

import com.turngame.domain.enums.ActionType;

public record UseSkillAction(String actorId, String targetId, String skillName, int damage) implements GameAction {
    @Override
    public ActionType actionType() {
        return ActionType.USE_SKILL;
    }
}
