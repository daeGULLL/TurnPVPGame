package com.turngame.engine.rules;

import com.turngame.domain.PlayerState;
import com.turngame.domain.defense.DefenseState;
import com.turngame.domain.defense.EvadeWindow;
import com.turngame.domain.skill.SkillCounter;
import com.turngame.domain.skill.SkillTemplate;
import com.turngame.engine.GameSession;

import java.util.Optional;

/**
 * 실제 데미지를 계산합니다.
 * 회피, 상쇄, 기본 데미지를 종합적으로 고려합니다.
 */
public class DamageResolver {

    /**
     * 최종 데미지를 계산합니다.
     * 
     * 프로세스:
     * 1. 공격 스킬 상쇄 확인 - 상쇄 가능한가?
     * 2. 방어자 회피 여부 확인 - 회피 중인가?
     * 3. 회피 시간 겹침도 계산 - 얼마나 회피했는가?
     * 4. 최종 데미지 = 기본 데미지 * (1 - 회피율) * 상쇄율
     */
    public static int calculateFinalDamage(
            SkillTemplate attackSkill,
            int baseDamage,
            PlayerState defender,
            GameSession session
    ) {
        int currentDamage = baseDamage;

        // Step 1: 상쇄 확인
        Optional<Integer> counteredDamage = checkSkillCounters(attackSkill, baseDamage, defender, session);
        if (counteredDamage.isPresent()) {
            return counteredDamage.get();  // 0 또는 감소된 값
        }

        // Step 2 & 3: 회피 확인
        Optional<DefenseState> defense = defender.getCurrentDefense();
        if (defense.isPresent()) {
            double evadeSuccessRatio = calculateEvadeRatio(
                    defense.get(),
                    attackSkill,
                    defender,
                    session
            );
            currentDamage = (int) (baseDamage * (1.0 - evadeSuccessRatio));
        }

        return Math.max(0, currentDamage);
    }

    /**
     * 스킬 상쇄 확인: 방어자가 사용한 방어 스킬이 공격을 상쇄할 수 있는가?
     * @return 상쇄된 경우 최종 데미지 (0 또는 감소값), 상쇄 없으면 empty
     */
    private static Optional<Integer> checkSkillCounters(
            SkillTemplate attackSkill,
            int baseDamage,
            PlayerState defender,
            GameSession session
    ) {
        Optional<DefenseState> defense = defender.getCurrentDefense();
        if (defense.isEmpty()) {
            return Optional.empty();
        }

        // 방어 스킬 조회
        Optional<SkillTemplate> defenseSkillTemplate = session.getSkillTemplate(defense.get().defenseSkillName());
        if (defenseSkillTemplate.isEmpty()) {
            return Optional.empty();
        }

        SkillTemplate defenseSkill = defenseSkillTemplate.get();

        // 방어 스킬의 상쇄 목록에서 공격 스킬을 찾기
        for (SkillCounter counter : defenseSkill.counters()) {
            if (counter.targetSkillName().equalsIgnoreCase(attackSkill.name())) {
                // 상쇄 발견!
                return Optional.of(counter.applyCounterToDamage(baseDamage));
            }
        }

        return Optional.empty();
    }

    /**
     * 회피 성공 비율을 계산합니다.
     * 
     * 회피 성공 비율 = 공격 시간 범위 내에서 회피가 유효한 비율
     * 예: 공격 500ms, 회피 범위가 200ms 겹치면 회피율 40%
     * 
     * @return 회피율 (0.0 ~ 1.0)
     */
    private static double calculateEvadeRatio(
            DefenseState defense,
            SkillTemplate attackSkill,
            PlayerState defender,
            GameSession session
    ) {
        // 방어 스킬의 회피 시간 범위 구성
        long evadeDuration = getEvadeDuration(defense.defenseSkillName(), session);
        EvadeWindow evadeWindow = new EvadeWindow(
                defense.defenseStartTimeMs(),
                evadeDuration
        );

        // 공격 시간 범위와의 겹침도 계산
        int attackPrepareTime = attackSkill.prepareCastMs();
        long overlapRatio = Math.round(evadeWindow.calculateOverlapRatio(0, attackPrepareTime) * 100);

        // 방어자의 민첩성 적용 (민첩성이 높을수록 회피 성공률 증가)
        // 기본 회피율 (겹침도) + 민첩성 보너스
        double baseEvadeRatio = overlapRatio / 100.0;
        double agilityBonus = defender.agility() / 500.0;  // 최대 20% 추가 회피
        double finalEvadeRatio = Math.min(baseEvadeRatio + agilityBonus, 1.0);

        // 완전 회피인 경우 100% 회피율
        if (evadeWindow.isFullEvade(0, attackPrepareTime)) {
            return 1.0;
        }

        return finalEvadeRatio;
    }

    /**
     * 방어 스킬의 회피 지속 시간을 조회합니다.
     */
    private static long getEvadeDuration(String defenseSkillName, GameSession session) {
        Optional<SkillTemplate> skill = session.getSkillTemplate(defenseSkillName);
        if (skill.isPresent()) {
            return skill.get().evadeDurationMs();
        }
        return 100;  // 기본값 100ms
    }
}
