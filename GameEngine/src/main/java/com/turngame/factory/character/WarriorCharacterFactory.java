package com.turngame.factory.character;

import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.CharacterType;

import java.util.concurrent.ThreadLocalRandom;

public class WarriorCharacterFactory extends CharacterFactory {
    @Override
    public GameCharacter createCharacter(String playerId) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new GameCharacter(
                playerId,
                CharacterType.WARRIOR,
                "Frontline Warrior",
                random.nextInt(10, 31),
                random.nextInt(10, 31),
                random.nextInt(10, 31)
        );
    }
}
