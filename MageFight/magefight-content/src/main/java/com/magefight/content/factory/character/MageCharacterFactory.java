package com.magefight.content.factory.character;

import com.magefight.content.model.MageArchetype;
import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.CharacterType;
import com.turngame.factory.character.CharacterFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * MageFight 캐릭터 팩토리.
 * 아키타입 호칭과 스탯 성향은 MageArchetype에서 관리합니다.
 */
public class MageCharacterFactory extends CharacterFactory {
    private final MageArchetype archetype;

    public MageCharacterFactory() {
        this(MageArchetype.APPRENTICE);
    }

    public MageCharacterFactory(MageArchetype archetype) {
        this.archetype = archetype == null ? MageArchetype.APPRENTICE : archetype;
    }

    @Override
    public GameCharacter createCharacter(String playerId) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int strength = archetype.rollStrength(random);
        int agility = archetype.rollAgility(random);
        int intelligence = archetype.rollIntelligence(random);

        return new GameCharacter(
                playerId,
                CharacterType.MAGE,
                archetype.displayName(),
                strength,
                agility,
                intelligence
        );
    }
}