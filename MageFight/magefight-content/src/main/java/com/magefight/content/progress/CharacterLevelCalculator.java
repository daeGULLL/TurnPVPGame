package com.magefight.content.progress;

/**
 * 총 스킬 포인트로부터 캐릭터 레벨을 계산합니다.
 *
 * 레벨 경계:
 * - 레벨 0: 0 ~ 4 포인트
 * - 레벨 1: 5 ~ 24 포인트
 * - 레벨 2: 25 ~ 124 포인트
 * - 레벨 3: 125+ 포인트
 */
public final class CharacterLevelCalculator {
    private CharacterLevelCalculator() {
    }

    public static int calculateLevel(int totalSkillPoints) {
        if (totalSkillPoints < 5) {
            return 0;
        } else if (totalSkillPoints < 25) {
            return 1;
        } else if (totalSkillPoints < 125) {
            return 2;
        } else {
            return 3;
        }
    }

    public static int pointsUntilNextLevel(int totalSkillPoints) {
        return switch (calculateLevel(totalSkillPoints)) {
            case 0 -> 5 - totalSkillPoints;
            case 1 -> 25 - totalSkillPoints;
            case 2 -> 125 - totalSkillPoints;
            case 3 -> 0;
            default -> 0;
        };
    }
}