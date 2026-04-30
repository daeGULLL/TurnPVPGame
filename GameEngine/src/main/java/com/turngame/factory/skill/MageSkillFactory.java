package com.turngame.factory.skill;

import com.turngame.domain.skill.SkillCounter;
import com.turngame.domain.skill.SkillEffect;
import com.turngame.domain.skill.SkillTemplate;

import java.util.List;

public class MageSkillFactory extends SkillFactory {
    @Override
    public List<SkillTemplate> createStarterSkills() {
        return List.of(
            new SkillTemplate(
                "마나 방출",
                4,
                0,
                0.9,
                1,
                3,
                2,
                new SkillEffect(SkillEffect.AreaType.STATIC, 1, 0),
                List.of(new SkillCounter("마나 방출", "War Cry", SkillCounter.CounterType.PARTIAL_REDUCTION, 30))
            )
        );
    }
}
