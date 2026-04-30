package com.turngame.factory.skill;

import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;

import java.util.List;

public class RogueSkillFactory extends SkillFactory {
    @Override
    public List<SkillTemplate> createStarterSkills() {
        return List.of(
            new SkillTemplate("Backstab", 12, 2, 1.0, 0, 0, 0,
                new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0), List.of()),
            new SkillTemplate("Poison Edge", 7, 3, 1.0, 0, 0, 0,
                new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0), List.of()));
    }
}
