package com.magefight.content.model;

import com.turngame.domain.skill.SkillTemplate;

import java.util.List;

public record SkillTreeNode(
        String branchId,
        SkillTemplate skill,
        int inspirationCost,
        int requiredLevel,
        List<String> prerequisiteSkills
) {
}