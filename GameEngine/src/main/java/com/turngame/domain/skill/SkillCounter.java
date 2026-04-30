package com.turngame.domain.skill;

/**
 * 스킬이 다른 스킬에 대해 어떻게 상쇄하는지를 정의합니다.
 */
public record SkillCounter(
        String counterSkillName,        // 이 스킬의 이름
        String targetSkillName,        // 상쇄할 대상 스킬
        CounterType type,
        int damageReductionPercent     // type이 PARTIAL일 때만 사용 (0~100)
) {
    public enum CounterType {
        FULL_NEGATION,         // 스킬 완전 무효화
        PARTIAL_REDUCTION      // 데미지 감소 (damageReductionPercent%)
    }

    public SkillCounter {
        if (damageReductionPercent < 0 || damageReductionPercent > 100) {
            throw new IllegalArgumentException("damageReductionPercent must be 0~100");
        }
    }

    /**
     * 상쇄에 의한 최종 데미지 계산
     */
    public int applyCounterToDamage(int originalDamage) {
        if (type == CounterType.FULL_NEGATION) {
            return 0;
        }
        return (int) (originalDamage * (100 - damageReductionPercent) / 100.0);
    }
}
