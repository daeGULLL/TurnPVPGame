package com.turngame.factory.character;

import com.turngame.domain.character.GameCharacter;

public abstract class CharacterFactory {
    public abstract GameCharacter createCharacter(String playerId);
}
