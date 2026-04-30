package com.turngame.factory.skill;

import com.turngame.domain.skill.SkillTemplate;

import java.util.List;

public abstract class SkillFactory {
    public abstract List<SkillTemplate> createStarterSkills();
}
