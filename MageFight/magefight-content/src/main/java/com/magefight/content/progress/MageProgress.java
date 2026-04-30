package com.magefight.content.progress;

import com.magefight.content.model.MageArchetype;
import com.magefight.content.model.MageSkillTree;
import com.magefight.content.model.SkillTreeNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 플레이어의 캐릭터 진행 상황을 추적합니다.
 *
 * 캐릭터 레벨은 스킬 포인트로부터 자동 계산됩니다.
 * 스킬 포인트 = 각 스킬의 (숙련도 × 계층 배수) 합계
 *
 * archetype은 한 번만 선택 가능한 불가역적 호칭입니다.
 */
public class MageProgress {
    private static final String STARTER_SKILL_NAME = "마나 방출";

    private int wins;
    private MageArchetype selectedArchetype;
    private final Map<String, Integer> skillMasteryLevels;
    private final Map<String, Integer> skillPracticePoints;
    private int inspirationPoints;

    public MageProgress(int wins, MageArchetype selectedArchetype, Map<String, Integer> skillMasteryLevels) {
        this(wins, selectedArchetype, skillMasteryLevels, new HashMap<>(), 0);
    }

    public MageProgress(
            int wins,
            MageArchetype selectedArchetype,
            Map<String, Integer> skillMasteryLevels,
            Map<String, Integer> skillPracticePoints,
            int inspirationPoints
    ) {
        this.wins = Math.max(0, wins);
        this.selectedArchetype = selectedArchetype;
        this.skillMasteryLevels = skillMasteryLevels == null ? new HashMap<>() : new HashMap<>(skillMasteryLevels);
        this.skillPracticePoints = skillPracticePoints == null ? new HashMap<>() : new HashMap<>(skillPracticePoints);
        this.inspirationPoints = Math.max(0, inspirationPoints);
        ensureStarterSkill();
    }

    public static MageProgress starter() {
        Map<String, Integer> starterMastery = new HashMap<>();
        starterMastery.put(STARTER_SKILL_NAME, 1);
        return new MageProgress(0, MageArchetype.APPRENTICE, starterMastery, new HashMap<>(), 0);
    }

    public int level() {
        int totalPoints = calculateTotalSkillPoints();
        return CharacterLevelCalculator.calculateLevel(totalPoints);
    }

    public int calculateTotalSkillPoints() {
        Map<String, Integer> tierMap = SkillTierResolver.buildSkillTierMap(skillMasteryLevels.keySet());
        return SkillPointCalculator.calculateTotalPoints(skillMasteryLevels, tierMap);
    }

    public int wins() {
        return wins;
    }

    public MageArchetype selectedArchetype() {
        return selectedArchetype;
    }

    public Map<String, Integer> skillMasteryLevels() {
        return Collections.unmodifiableMap(skillMasteryLevels);
    }

    public Map<String, Integer> skillPracticePoints() {
        return Collections.unmodifiableMap(skillPracticePoints);
    }

    public int inspirationPoints() {
        return inspirationPoints;
    }

    public int getSkillMastery(String skillName) {
        return skillMasteryLevels.getOrDefault(skillName, 0);
    }

    public int getSkillPracticePoints(String skillName) {
        return skillPracticePoints.getOrDefault(skillName, 0);
    }

    public boolean hasLearnedSkill(String skillName) {
        return getSkillMastery(skillName) > 0;
    }

    public List<String> learnedSkillNames() {
        return List.copyOf(new ArrayList<>(skillMasteryLevels.keySet()));
    }

    public void updateSkillMastery(String skillName, int masteryLevel) {
        if (masteryLevel >= 1) {
            skillMasteryLevels.put(skillName, Math.max(getSkillMastery(skillName), masteryLevel));
        }
    }

    public int recordSkillUse(String skillName) {
        if (!hasLearnedSkill(skillName)) {
            return 0;
        }

        int practice = getSkillPracticePoints(skillName) + 1;
        int mastery = Math.max(1, getSkillMastery(skillName));
        int gainedLevels = 0;

        while (practice >= 10) {
            practice -= 10;
            mastery += 1;
            gainedLevels += 1;
        }

        skillPracticePoints.put(skillName, practice);
        skillMasteryLevels.put(skillName, mastery);
        inspirationPoints += gainedLevels;
        return gainedLevels;
    }

    public boolean canLearnSkill(SkillTreeNode node, MageSkillTree tree) {
        if (node == null || tree == null) {
            return false;
        }
        String skillName = node.skill().name();
        if (hasLearnedSkill(skillName)) {
            return false;
        }
        if (inspirationPoints < node.inspirationCost()) {
            return false;
        }
        for (String prerequisite : node.prerequisiteSkills()) {
            if (!hasLearnedSkill(prerequisite)) {
                return false;
            }
        }
        return true;
    }

    public boolean learnSkill(SkillTreeNode node, MageSkillTree tree) {
        if (!canLearnSkill(node, tree)) {
            return false;
        }
        inspirationPoints = Math.max(0, inspirationPoints - node.inspirationCost());
        skillMasteryLevels.put(node.skill().name(), 1);
        skillPracticePoints.putIfAbsent(node.skill().name(), 0);
        return true;
    }

    public void addInspiration(int amount) {
        inspirationPoints = Math.max(0, inspirationPoints + amount);
    }

    private void ensureStarterSkill() {
        if (selectedArchetype == MageArchetype.APPRENTICE && skillMasteryLevels.isEmpty()) {
            skillMasteryLevels.put(STARTER_SKILL_NAME, 1);
        }
    }

    public void registerWin() {
        wins += 1;
    }

    public boolean hasSelectedArchetype() {
        return selectedArchetype != null;
    }

    public boolean isSelected(MageArchetype archetype) {
        return selectedArchetype == archetype;
    }

    public void selectArchetype(MageArchetype archetype) {
        if (selectedArchetype != null) {
            throw new IllegalStateException("archetype is already selected: " + selectedArchetype.displayName());
        }
        selectedArchetype = archetype;
    }
}