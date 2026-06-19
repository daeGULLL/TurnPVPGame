package com.magefight.content.model;

public record SkillVisualStyle(
        String coreColorHex,
        String glowColorHex,
        String trailColorHex,
        double launchStartProgress,
        double launchDurationProgress,
        float trailWidth,
        int radius
) {
}
