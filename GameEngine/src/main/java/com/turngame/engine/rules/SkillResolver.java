package com.turngame.engine.rules;

import com.turngame.domain.PlayerSkillMastery;
import com.turngame.domain.PlayerState;
import com.turngame.domain.skill.SkillDependency;
import com.turngame.domain.skill.SkillTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 스킬 실행 시점에 스킬의 효과를 계산합니다.
 * - 성공확률 평가 (숙련도 보정 포함)
 * - 에너지 소모 계산 (실패/성공 구분)
 * - 스킬트리 의존성에 따른 수정값 적용
 */
public class SkillResolver {
    private static final Random random = new Random();

    /**
     * 스킬 시전 가능 여부를 검사합니다.
     * @return 시전 불가능한 이유, 또는 empty if 가능
     */
    public static Optional<String> validateSkillCast(
            SkillTemplate skill,
            PlayerState actor,
            Map<String, SkillTemplate> allSkills
    ) {
        // 에너지 확인 (실패해도 소모되는 에너지 기준)
        int requiredEnergy = skill.failEnergyCost();
        if (!actor.hasEnergy(requiredEnergy)) {
            return Optional.of("Not enough energy. Required: " + requiredEnergy + ", Have: " + actor.energy());
        }

        return Optional.empty();
    }

    /**
     * 스킬 시전 성공 여부를 판정합니다. (주사위 굴림)
     * 숙련도가 높을수록 성공 확률이 증가합니다.
     */
    public static boolean resolveSuccess(
            SkillTemplate skill,
            PlayerState actor
    ) {
        double baseProbability = skill.baseSuccessProbability();
        
        // 숙련도에 따른 성공확률 보정
        Optional<PlayerSkillMastery> mastery = actor.getSkillMastery(skill.name());
        if (mastery.isPresent()) {
            double bonus = mastery.get().successProbabilityBonus();
            baseProbability = Math.min(baseProbability + bonus, 1.0);
        }

        return random.nextDouble() < baseProbability;
    }

    /**
     * 스킬 시전에 소모될 에너지를 계산합니다.
     * 성공 여부에 따라 다른 값을 반환합니다.
     */
    public static int calculateEnergyCost(
            SkillTemplate skill,
            boolean success
    ) {
        if (success) {
            return skill.failEnergyCost() + skill.successEnergyCost();
        } else {
            return skill.failEnergyCost();
        }
    }

    /**
     * 현재 숙련도를 고려한 시전 준비 시간(ms)을 계산합니다.
     */
    public static int calculatePrepareCastTime(
            SkillTemplate skill,
            PlayerState actor
    ) {
        int baseTime = skill.prepareCastMs();
        
        Optional<PlayerSkillMastery> mastery = actor.getSkillMastery(skill.name());
        if (mastery.isPresent()) {
            double reductionRatio = mastery.get().prepareCastTimeReductionRatio();
            return (int) (baseTime * (1.0 - reductionRatio));
        }

        return baseTime;
    }

    /**
     * 스킬트리 의존성에 따른 수정값을 적용합니다.
     * 예: A스킬이 B스킬의 성공확률을 +20% 증가시킨다면, B시전 시 +20%를 적용
     */
    public static double applyDependencyModifiers(
            SkillTemplate skill,
            Map<String, SkillTemplate> allSkills,
            PlayerState actor,
            SkillDependency.DependencyType typeToApply
    ) {
        double totalModifier = 0.0;

        for (SkillTemplate otherSkill : allSkills.values()) {
            for (SkillDependency dep : otherSkill.dependencies()) {
                if (dep.affectedSkillName().equals(skill.name()) 
                    && dep.type() == typeToApply) {
                    // otherSkill이 현재 actor가 소유하고 있는지 확인
                    Optional<PlayerSkillMastery> mastery = actor.getSkillMastery(otherSkill.name());
                    if (mastery.isPresent()) {
                        totalModifier += dep.modifierValue();
                    }
                }
            }
        }

        return totalModifier;
    }

    /**
     * 숙련도를 얻습니다.
     * 스킬 사용 성공 시 더 많은 경험치를 얻습니다.
     */
    public static void awardMasteryExperience(
            SkillTemplate skill,
            PlayerState actor,
            boolean success
    ) {
        long xp = success ? 50 : 25;
        actor.gainSkillExperience(skill.name(), xp);
    }

    /**
     * 피해량을 최종 계산합니다 (숙련도 보정 포함)
     */
    public static int calculateFinalDamage(
            SkillTemplate skill,
            PlayerState actor,
            Map<String, SkillTemplate> allSkills
    ) {
        int baseDamage = skill.baseDamage();

        // 스킬트리에서 데미지 보너스 적용
        double damageBonus = applyDependencyModifiers(
                skill, allSkills, actor, 
                SkillDependency.DependencyType.DAMAGE_BONUS
        );
        
        return (int) (baseDamage * (1.0 + damageBonus / 100.0));
    }
}
