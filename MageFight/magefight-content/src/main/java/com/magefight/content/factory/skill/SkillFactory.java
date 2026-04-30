package com.magefight.content.factory.skill;

import com.turngame.domain.skill.SkillTemplate;

import java.util.List;

public interface SkillFactory {
    List<SkillTemplate> createExtraSkills();
}