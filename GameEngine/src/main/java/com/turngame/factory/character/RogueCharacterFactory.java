package com.turngame.factory.character;

import com.turngame.domain.character.GameCharacter;
import com.turngame.domain.enums.CharacterType;

import java.util.concurrent.ThreadLocalRandom;

public class RogueCharacterFactory extends CharacterFactory {
    @Override
    public GameCharacter createCharacter(String playerId) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new GameCharacter(
                playerId,
                CharacterType.ROGUE,
                "Shadow Rogue",
                random.nextInt(10, 31),
                random.nextInt(10, 31),
                random.nextInt(10, 31)
        );
    }
}
