package com.turngame.domain.defense;

/**
 * 플레이어의 방어 상태를 나타냅니다.
 * DefendAction 시에 설정되며, 다음 상대 공격 시에 회피 판정에 사용됩니다.
 */
public class DefenseState {
    private final String playerId;
    private final String defenseSkillName;  // 사용한 방어 스킬 (예: "Dodge", "Parry")
    private final long defenseStartTimeMs;  // 방어 시작 시간 (상대 턴 기준)

    public DefenseState(String playerId, String defenseSkillName, long defenseStartTimeMs) {
        this.playerId = playerId;
        this.defenseSkillName = defenseSkillName;
        this.defenseStartTimeMs = defenseStartTimeMs;
    }

    public String playerId() {
        return playerId;
    }

    public String defenseSkillName() {
        return defenseSkillName;
    }

    public long defenseStartTimeMs() {
        return defenseStartTimeMs;
    }
}
