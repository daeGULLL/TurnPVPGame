package com.magefight.content.model;

import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.skill.SkillTemplate;

import java.util.List;
import java.util.Objects;

public record FighterSpec(GameCharacter character, List<SkillTemplate> skills) {
    public FighterSpec {
        Objects.requireNonNull(character, "character cannot be null");
        skills = List.copyOf(skills);
    }

    public String title() {
        return character.title();
    }

    public int hpBonus() {
        return character.hpBonus();
    }

    public int attackBonus() {
        return character.attackBonus();
    }
}