package com.turngame.engine.command;

import com.turngame.domain.enums.ActionType;

public record UseSkillAction(
        String actorId,
        String targetId,
        String skillName,
        int damage,
        Integer targetCol,
        Integer targetRow
) implements GameAction {
    public UseSkillAction(String actorId, String targetId, String skillName, int damage) {
        this(actorId, targetId, skillName, damage, null, null);
    }

    @Override
    public ActionType actionType() {
        return ActionType.USE_SKILL;
    }
}
