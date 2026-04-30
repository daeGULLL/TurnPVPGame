package com.magefight.content.factory.character;

import com.magefight.content.model.MageArchetype;
import com.turngame.factory.character.CharacterFactory;

public final class MageCharacterFactoryProvider {
    private MageCharacterFactoryProvider() {
    }

    public static CharacterFactory byArchetype(MageArchetype archetype) {
        return new MageCharacterFactory(archetype);
    }
}