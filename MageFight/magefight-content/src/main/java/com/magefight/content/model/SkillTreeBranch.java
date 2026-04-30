package com.magefight.content.model;

import java.util.List;
import java.util.Objects;

public record SkillTreeBranch(
        String branchId,
        String displayName,
        List<SkillTreeNode> nodes
) {
    public SkillTreeBranch {
        Objects.requireNonNull(branchId, "branchId cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        nodes = List.copyOf(nodes);
    }
}