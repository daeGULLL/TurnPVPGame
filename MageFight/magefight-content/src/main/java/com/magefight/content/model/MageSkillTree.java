package com.magefight.content.model;

import com.magefight.content.progress.MageProgress;
import com.turngame.domain.skill.SkillTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

public record MageSkillTree(
        MageArchetype archetype,
        List<SkillTreeBranch> branches,
        List<SkillTreeLink> links
) {
    public MageSkillTree {
        Objects.requireNonNull(archetype, "archetype cannot be null");
        branches = List.copyOf(branches);
        links = List.copyOf(links);
    }

    public List<SkillTreeNode> nodes() {
        List<SkillTreeNode> flattened = new ArrayList<>();
        for (SkillTreeBranch branch : branches) {
            flattened.addAll(branch.nodes());
        }
        return List.copyOf(flattened);
    }

    public Optional<SkillTreeNode> findNode(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return Optional.empty();
        }
        return nodes().stream()
                .filter(node -> node.skill().name().equalsIgnoreCase(skillName))
                .findFirst();
    }

    public Map<String, SkillTreeNode> nodeMap() {
        Map<String, SkillTreeNode> map = new LinkedHashMap<>();
        for (SkillTreeNode node : nodes()) {
            map.putIfAbsent(node.skill().name(), node);
        }
        return Map.copyOf(map);
    }

    public boolean canLearn(String skillName, MageProgress progress) {
        return findNode(skillName)
                .map(node -> progress.canLearnSkill(node, this))
                .orElse(false);
    }

    public List<SkillTemplate> learnedSkills(MageProgress progress) {
        List<SkillTemplate> learned = new ArrayList<>();
        for (SkillTreeNode node : nodes()) {
            if (progress.hasLearnedSkill(node.skill().name())) {
                learned.add(node.skill());
            }
        }
        return List.copyOf(learned);
    }

    public List<SkillTreeNode> learnableNodes(MageProgress progress) {
        List<SkillTreeNode> learnable = new ArrayList<>();
        for (SkillTreeNode node : nodes()) {
            if (progress.canLearnSkill(node, this)) {
                learnable.add(node);
            }
        }
        return List.copyOf(learnable);
    }
}