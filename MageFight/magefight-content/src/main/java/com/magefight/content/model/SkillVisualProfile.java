package com.magefight.content.model;

public record SkillVisualProfile(
        String skillName,
        SkillVisualStyle style,
        String projectileImagePath,
        Integer projectileImageSize
) {
}
