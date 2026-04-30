package com.turngame.domain.skill;

/**
 * 스킬트리: 한 스킬이 다른 스킬에 미치는 영향을 정의합니다.
 * 예: "Flame Strike"가 "Mana Burn"의 성공확률을 +20% 증가시킨다
 */
public record SkillDependency(
        String affectedSkillName,           // 영향을 받는 스킬 이름
        DependencyType type,
        double modifierValue                // 수정값 (% 또는 절대값)
) {
    public enum DependencyType {
        SUCCESS_PROBABILITY_BONUS,          // 성공확률 증가 (%)
        ENERGY_COST_REDUCTION,              // 에너지 소모 감소 (%)
        AREA_RADIUS_BONUS,                  // 맵 영향 범위 증가 (절대값)
        PREPARE_CAST_TIME_REDUCTION,        // 시전 준비 시간 감소 (%)
        DURATION_BONUS,                     // 시전 후 지속 시간 증가 (턴)
        DAMAGE_BONUS                        // 데미지 증가 (%)
    }
}
