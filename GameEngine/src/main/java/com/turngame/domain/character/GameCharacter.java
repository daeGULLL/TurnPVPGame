package com.turngame.domain.character;

import com.turngame.domain.enums.CharacterType;

public record GameCharacter(
		String playerId,
		CharacterType type,
		String title,
		int strength,
		int agility,
		int intelligence
) {
	public int maxHp() {
		return strength + agility + 20;
	}

	public int hpBonus() {
		return maxHp() - 100;
	}

	public int attackBonus() {
		return Math.max(1, (strength + intelligence) / 8);
	}
}
