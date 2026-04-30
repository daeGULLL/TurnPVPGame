package com.magefight.content.progress;

import java.util.Map;

/**
 * 스킬 숙련도로부터 스킬 포인트를 계산합니다.
 *
 * 스킬 포인트 = 숙련도 레벨 × 스킬 계층 배수
 * - 1계층 스킬: 배수 = 1
 * - 2계층 스킬: 배수 = 5
 * - 3계층 스킬: 배수 = 10
 */
public final class SkillPointCalculator {
    private SkillPointCalculator() {
    }

    public static int calculateTotalPoints(
            Map<String, Integer> skillMasteryLevels,
            Map<String, Integer> skillTiers) {
        int totalPoints = 0;

        for (Map.Entry<String, Integer> entry : skillMasteryLevels.entrySet()) {
            String skillName = entry.getKey();
            int masteryLevel = entry.getValue();
            Integer tier = skillTiers.get(skillName);

            if (tier != null && tier >= 1 && tier <= 3 && masteryLevel >= 1) {
                int tierMultiplier = getTierMultiplier(tier);
                totalPoints += masteryLevel * tierMultiplier;
            }
        }

        return totalPoints;
    }

    private static int getTierMultiplier(int tier) {
        return switch (tier) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 10;
            default -> throw new IllegalArgumentException("Invalid tier: " + tier);
        };
    }
}