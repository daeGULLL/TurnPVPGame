package com.turngame.factory.skill;

import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;

import java.util.List;

public class WarriorSkillFactory extends SkillFactory {
    @Override
    public List<SkillTemplate> createStarterSkills() {
        return List.of(
            new SkillTemplate("Shield Bash", 8, 2, 1.0, 0, 0, 0,
                new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0), List.of()),
            new SkillTemplate("War Cry", 5, 3, 1.0, 0, 0, 0,
                new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0), List.of())
        );
    }
}
