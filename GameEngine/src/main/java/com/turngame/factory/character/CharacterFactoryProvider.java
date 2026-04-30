package com.turngame.factory.character;

import com.turngame.domain.enums.CharacterType;

import java.util.Locale;

public final class CharacterFactoryProvider {
    private CharacterFactoryProvider() {
    }

    public static CharacterFactory byPreference(String preferredType) {
        String normalized = preferredType == null ? "" : preferredType.trim().toUpperCase(Locale.ROOT);
        if (CharacterType.ROGUE.name().equals(normalized)) {
            return new RogueCharacterFactory();
        }
        if (CharacterType.MAGE.name().equals(normalized)) {
            return new MageCharacterFactory();
        }
        return new WarriorCharacterFactory();
    }
}
