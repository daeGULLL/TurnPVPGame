package com.turngame.domain;

/**
 * 플레이어별 스킬 숙련도를 관리합니다.
 * 각 플레이어는 자신이 배운 스킬에 대해 고유한 숙련도 레벨을 가집니다.
 * 
 * 숙련도 레벨에 따라:
 * - 시전 준비 시간이 감소합니다 (레벨당 5% 감소)
 * - 성공확률이 증가합니다 (레벨당 2% 증가)
 */
public class PlayerSkillMastery {
    private final String skillName;
    private int masteryLevel;
    private long totalExperience;

    public PlayerSkillMastery(String skillName) {
        this.skillName = skillName;
        this.masteryLevel = 1;
        this.totalExperience = 0;
    }

    public String skillName() {
        return skillName;
    }

    public int masteryLevel() {
        return masteryLevel;
    }

    public long totalExperience() {
        return totalExperience;
    }

    /**
     * 스킬 사용 시 경험치를 획득합니다.
     * 100 XP당 레벨이 1 상승합니다.
     */
    public void gainExperience(long xp) {
        this.totalExperience += xp;
        int newLevel = (int) (totalExperience / 100) + 1;
        if (newLevel > masteryLevel) {
            this.masteryLevel = newLevel;
        }
    }

    /**
     * 현재 숙련도에 따른 시전 준비 시간 감소율을 반환합니다. (0.0 ~ 1.0)
     * 예: 레벨 5면 0.25 (25% 감소)
     */
    public double prepareCastTimeReductionRatio() {
        return Math.min(0.05 * (masteryLevel - 1), 0.50);  // 최대 50% 감소
    }

    /**
     * 현재 숙련도에 따른 성공확률 증가값을 반환합니다.
     * 예: 레벨 5면 0.08 (+8%)
     */
    public double successProbabilityBonus() {
        return Math.min(0.02 * (masteryLevel - 1), 0.30);  // 최대 30% 증가
    }

    @Override
    public String toString() {
        return String.format("%s(Level %d, XP: %d)", skillName, masteryLevel, totalExperience);
    }
}
