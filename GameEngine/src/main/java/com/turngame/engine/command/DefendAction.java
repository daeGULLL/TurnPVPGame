package com.turngame.engine.command;

import com.turngame.domain.enums.ActionType;

public class DefendAction implements GameAction {
    private final String actorId;
    private final String evadeSkillName;
    private final long evadeStartTimeMs;

    public DefendAction(String actorId) {
        this(actorId, "Dodge", System.currentTimeMillis());
    }

    public DefendAction(String actorId, String evadeSkillName, long evadeStartTimeMs) {
        this.actorId = actorId;
        this.evadeSkillName = evadeSkillName;
        this.evadeStartTimeMs = evadeStartTimeMs;
    }

    @Override
    public ActionType actionType() {
        return ActionType.DEFEND;
    }

    @Override
    public String actorId() {
        return actorId;
    }

    public String evadeSkillName() {
        return evadeSkillName;
    }

    public long evadeStartTimeMs() {
        return evadeStartTimeMs;
    }
}
