package com.turngame.domain.skill;

/**
 * 스킬의 맵 영향 범위와 시간 효과를 정의합니다.
 * 동적(Dynamic): 시전자 위치 기반으로 매 턴 확산
 * 정적(Static): 시전 시점에 고정된 영역
 */
public record SkillEffect(
        AreaType areaType,
        int areaRadius,
        int durationTurns
) {
    public enum AreaType {
        STATIC,    // 시전 시점에 고정 범위
        DYNAMIC    // 시전자 위치 기반 동적 확산
    }

    public SkillEffect {
        if (areaRadius < 0 || durationTurns < 0) {
            throw new IllegalArgumentException("areaRadius and durationTurns must be non-negative");
        }
    }
}
