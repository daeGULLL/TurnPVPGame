package com.turngame.factory.skill;

import com.turngame.domain.enums.CharacterType;

public final class SkillFactoryProvider {
    private SkillFactoryProvider() {
    }

    public static SkillFactory byCharacterType(CharacterType type) {
        if (type == CharacterType.ROGUE) {
            return new RogueSkillFactory();
        }
        if (type == CharacterType.MAGE) {
            return new MageSkillFactory();
        }
        return new WarriorSkillFactory();
    }
}
