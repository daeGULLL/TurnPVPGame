package com.turngame.domain.skill;

import com.turngame.domain.defense.EvadeWindow;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 스킬의 불변 템플릿입니다. 모든 플레이어가 공유하는 스킬의 기본 정의입니다.
 * 성공확률, 에너지, 타이밍, 맵 효과 등 정적 정보를 포함합니다.
 * 
 * 숙련도는 PlayerSkillMastery에서 관리되며, 이를 통해 성공률 및 시전 시간을 변경할 수 있습니다.
 */
public record SkillTemplate(
        String name,
        int baseDamage,
        int cooldownTurns,
        double baseSuccessProbability,    // 0.0 ~ 1.0 (0% ~ 100%)
        int failEnergyCost,               // 실패해도 소모되는 에너지
        int successEnergyCost,            // 성공 시 추가 소모 에너지
        int prepareCastMs,                // 시전 준비 시간 (밀리초)
        SkillEffect effect,
        List<SkillDependency> dependencies, // 이 스킬이 영향을 받는 다른 스킬들
        List<SkillCounter> counters,      // 이 스킬이 상쇄할 수 있는 다른 스킬들
        boolean isDefenseSkill,           // 방어/회피 스킬 여부
        int evadeDurationMs               // 방어 스킬일 경우, 회피 유효 시간 (밀리초)
) {
    public SkillTemplate {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(effect, "effect cannot be null");
        
        if (baseDamage < 0 || cooldownTurns < 0) {
            throw new IllegalArgumentException("baseDamage and cooldownTurns must be non-negative");
        }
        if (baseSuccessProbability < 0.0 || baseSuccessProbability > 1.0) {
            throw new IllegalArgumentException("baseSuccessProbability must be between 0.0 and 1.0");
        }
        if (failEnergyCost < 0 || successEnergyCost < 0 || prepareCastMs < 0 || evadeDurationMs < 0) {
            throw new IllegalArgumentException("costs and timing must be non-negative");
        }
    }

    /**
     * 편의 생성자: 의존성 없고 방어 스킬이 아닌 일반 스킬 생성
     */
    public SkillTemplate(
            String name,
            int baseDamage,
            int cooldownTurns,
            double baseSuccessProbability,
            int failEnergyCost,
            int successEnergyCost,
            int prepareCastMs,
            SkillEffect effect,
            List<SkillCounter> counters
    ) {
        this(name, baseDamage, cooldownTurns, baseSuccessProbability,
             failEnergyCost, successEnergyCost, prepareCastMs, effect, List.of(), counters, false, 0);
    }

    /**
     * 방어 스킬을 편리하게 생성합니다.
     */
    public static SkillTemplate defenseSkill(String name, int evadeDurationMs) {
        return new SkillTemplate(
                name,
                0,    // 데미지 없음
                0,    // 쿨다운 없음
                1.0,  // 100% 성공 (항상 방어 상태 진입)
                10,   // 기본 에너지 소비
                0,    // 추가 에너지 없음
                50,   // 기본 회피 준비 시간
                new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0),
                List.of(),
                List.of(),
                true,
                evadeDurationMs
        );
    }

    public List<SkillDependency> dependencies() {
        return Collections.unmodifiableList(dependencies);
    }

    public List<SkillCounter> counters() {
        return Collections.unmodifiableList(counters);
    }
}
