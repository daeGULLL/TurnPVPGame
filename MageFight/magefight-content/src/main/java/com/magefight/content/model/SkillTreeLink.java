package com.magefight.content.model;

import java.util.Objects;

public record SkillTreeLink(
        String fromBranchId,
        String fromSkillName,
        String toBranchId,
        String toSkillName
) {
    public SkillTreeLink {
        Objects.requireNonNull(fromBranchId, "fromBranchId cannot be null");
        Objects.requireNonNull(fromSkillName, "fromSkillName cannot be null");
        Objects.requireNonNull(toBranchId, "toBranchId cannot be null");
        Objects.requireNonNull(toSkillName, "toSkillName cannot be null");
    }
}