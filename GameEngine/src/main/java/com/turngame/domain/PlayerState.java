package com.turngame.domain;

import com.turngame.domain.defense.DefenseState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PlayerState {
    private static final int DEFAULT_MOVE_INTERVAL_MS = 2000;
    private static final int DEFAULT_MOVE_RANGE = 1;

    private int hp;
    private int maxHp;
    private int strength;
    private int agility;
    private int intelligence;
    private int selfIntIncreaseCount;
    private int energy;
    private int maxEnergy;
    private int energySpentInWindow;
    private int maxEnergySpendPerWindow;
    private final Map<String, PlayerSkillMastery> skillMasteries;
    private final Map<String, MovementRuleModifier> movementRuleModifiers;
    private Optional<DefenseState> currentDefense;          // 현재 방어 상태
    private int moveIntervalMs;
    private int moveRange;
    private boolean diagonalMoveAllowed;
    private long lastMoveAtMs;

    public PlayerState(int hp) {
        this.hp = hp;
        this.maxHp = hp;
        this.agility = 20;
        this.strength = 20;
        this.intelligence = 20;
        this.selfIntIncreaseCount = 0;
        this.energy = 100;
        this.maxEnergy = 100;
        this.energySpentInWindow = 0;
        this.maxEnergySpendPerWindow = Integer.MAX_VALUE;
        this.skillMasteries = new HashMap<>();
        this.movementRuleModifiers = new HashMap<>();
        this.currentDefense = Optional.empty();
        this.moveIntervalMs = DEFAULT_MOVE_INTERVAL_MS;
        this.moveRange = DEFAULT_MOVE_RANGE;
        this.diagonalMoveAllowed = false;
        this.lastMoveAtMs = Long.MIN_VALUE / 4;
    }

    public PlayerState(int hp, int maxEnergy) {
        this(hp);
        this.energy = maxEnergy;
        this.maxEnergy = maxEnergy;
    }

    public PlayerState(int hp, int maxEnergy, int agility, int strength, int intelligence) {
        this.strength = Math.max(0, strength);
        this.agility = Math.max(0, agility);
        this.intelligence = Math.max(0, intelligence);
        this.maxHp = this.strength + this.agility + 20;
        this.hp = Math.min(Math.max(0, hp), this.maxHp);
        this.selfIntIncreaseCount = 0;
        this.energy = maxEnergy;
        this.maxEnergy = maxEnergy;
        this.energySpentInWindow = 0;
        this.maxEnergySpendPerWindow = Integer.MAX_VALUE;
        this.skillMasteries = new HashMap<>();
        this.movementRuleModifiers = new HashMap<>();
        this.currentDefense = Optional.empty();
        this.moveIntervalMs = DEFAULT_MOVE_INTERVAL_MS;
        this.moveRange = DEFAULT_MOVE_RANGE;
        this.diagonalMoveAllowed = false;
        this.lastMoveAtMs = Long.MIN_VALUE / 4;
    }

    public static PlayerState fromCoreStats(int strength, int agility, int intelligence, int maxEnergy) {
        int computedHp = Math.max(0, strength) + Math.max(0, agility) + 20;
        return new PlayerState(computedHp, maxEnergy, agility, strength, intelligence);
    }

    public int hp() {
        return hp;
    }

    public int maxHp() {
        return maxHp;
    }

    public int agility() {
        return agility;
    }

    public int strength() {
        return strength;
    }

    public int intelligence() {
        return intelligence;
    }

    public void setAgility(int agility) {
        this.agility = Math.max(0, agility);
        recomputeMaxHp();
    }

    public void setStrength(int strength) {
        this.strength = Math.max(0, strength);
        recomputeMaxHp();
    }

    public void setIntelligence(int intelligence) {
        this.intelligence = Math.max(0, intelligence);
    }

    public boolean increaseAgility(int amount) {
        if (amount <= 0) {
            return false;
        }
        this.agility += amount;
        recomputeMaxHp();
        return true;
    }

    public boolean increaseStrength(int amount) {
        if (amount <= 0) {
            return false;
        }
        this.strength += amount;
        recomputeMaxHp();
        return true;
    }

    public boolean increaseIntelligence(boolean useItem) {
        if (selfIntIncreaseCount < 5) {
            this.intelligence += 1;
            selfIntIncreaseCount += 1;
            return true;
        }
        if (useItem) {
            this.intelligence += 1;
            return true;
        }
        return false;
    }

    public boolean isAlive() {
        return hp > 0;
    }

    public void takeDamage(int damage) {
        this.hp = Math.max(0, this.hp - damage);
    }

    private void recomputeMaxHp() {
        int oldMax = this.maxHp;
        this.maxHp = this.strength + this.agility + 20;
        if (this.maxHp > oldMax) {
            this.hp = Math.min(this.maxHp, this.hp + (this.maxHp - oldMax));
        } else {
            this.hp = Math.min(this.hp, this.maxHp);
        }
    }

    // ===== Defense Management =====
    public void setDefense(DefenseState defense) {
        this.currentDefense = Optional.of(defense);
    }

    public Optional<DefenseState> getCurrentDefense() {
        return currentDefense;
    }

    public void clearDefense() {
        this.currentDefense = Optional.empty();
    }

    public boolean isDefending() {
        return currentDefense.isPresent();
    }

    // ===== Energy Management =====
    public int energy() {
        return energy;
    }

    public int maxEnergy() {
        return maxEnergy;
    }

    public void restoreEnergy(int amount) {
        this.energy = Math.min(maxEnergy, energy + amount);
    }

    public boolean hasEnergy(int required) {
        return energy >= required;
    }

    public void drainEnergy(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        this.energy = Math.max(0, energy - amount);
    }

    public int energySpentInWindow() {
        return energySpentInWindow;
    }

    public int maxEnergySpendPerWindow() {
        return maxEnergySpendPerWindow;
    }

    public void setMaxEnergySpendPerWindow(int cap) {
        this.maxEnergySpendPerWindow = cap <= 0 ? Integer.MAX_VALUE : cap;
    }

    public boolean canSpendEnergyThisWindow(int amount) {
        if (amount < 0) {
            return false;
        }
        if (maxEnergySpendPerWindow == Integer.MAX_VALUE) {
            return true;
        }
        return (energySpentInWindow + amount) <= maxEnergySpendPerWindow;
    }

    public void recordEnergySpentInWindow(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        energySpentInWindow += amount;
    }

    public void resetEnergySpentInWindow() {
        energySpentInWindow = 0;
    }

    // ===== Skill Mastery Management =====
    public void registerSkill(String skillName) {
        skillMasteries.putIfAbsent(skillName, new PlayerSkillMastery(skillName));
        applyMovementRuleModifierIfExists(skillName);
    }

    public Optional<PlayerSkillMastery> getSkillMastery(String skillName) {
        return Optional.ofNullable(skillMasteries.get(skillName));
    }

    public Map<String, PlayerSkillMastery> allSkillMasteries() {
        return new HashMap<>(skillMasteries);
    }

    public void gainSkillExperience(String skillName, long xp) {
        skillMasteries.computeIfAbsent(skillName, s -> new PlayerSkillMastery(skillName))
                .gainExperience(xp);
    }

    // ===== Movement Rule Management =====
    public int moveIntervalMs() {
        return moveIntervalMs;
    }

    public int moveRange() {
        return moveRange;
    }

    public boolean diagonalMoveAllowed() {
        return diagonalMoveAllowed;
    }

    public long lastMoveAtMs() {
        return lastMoveAtMs;
    }

    public boolean canMoveNow(long requestedAtMs) {
        return requestedAtMs - lastMoveAtMs >= moveIntervalMs;
    }

    public boolean canReachCell(int fromCol, int fromRow, int toCol, int toRow) {
        int deltaCol = Math.abs(fromCol - toCol);
        int deltaRow = Math.abs(fromRow - toRow);
        if (deltaCol == 0 && deltaRow == 0) {
            return false;
        }

        if (diagonalMoveAllowed) {
            return Math.max(deltaCol, deltaRow) <= moveRange;
        }
        return (deltaCol + deltaRow) <= moveRange;
    }

    public void markMoved(long movedAtMs) {
        this.lastMoveAtMs = movedAtMs;
    }

    public void registerMovementRuleModifier(String skillName, int moveIntervalMs, int moveRange, boolean diagonalMoveAllowed) {
        movementRuleModifiers.put(skillName,
                new MovementRuleModifier(
                        Math.max(250, moveIntervalMs),
                        Math.max(1, moveRange),
                        diagonalMoveAllowed));
    }

    private void applyMovementRuleModifierIfExists(String skillName) {
        MovementRuleModifier modifier = movementRuleModifiers.get(skillName);
        if (modifier == null) {
            return;
        }
        this.moveIntervalMs = modifier.moveIntervalMs();
        this.moveRange = modifier.moveRange();
        this.diagonalMoveAllowed = modifier.diagonalMoveAllowed();
    }

    private record MovementRuleModifier(int moveIntervalMs, int moveRange, boolean diagonalMoveAllowed) {
    }
}
