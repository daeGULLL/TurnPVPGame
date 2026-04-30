package com.turngame.domain.defense;

/**
 * 방어자가 회피를 시도할 때의 시간 정보입니다.
 * 공격 스킬의 시전 준비 시간과 겹쳐서 데미지 판정에 영향을 줍니다.
 */
public record EvadeWindow(
        long startTimeMs,     // 회피 시작 시간 (상대 턴 기준 0ms부터)
        long durationMs       // 회피 유효 시간 (밀리초)
) {
    public long endTimeMs() {
        return startTimeMs + durationMs;
    }

    /**
     * 공격 스킬의 시전 시간 범위와의 겹침 정도를 계산합니다.
     * @param attackStartMs 공격 시작 시간
     * @param attackDurationMs 공격 시전 시간
     * @return 겹치는 비율 (0.0 ~ 1.0)
     */
    public double calculateOverlapRatio(long attackStartMs, long attackDurationMs) {
        long attackEndMs = attackStartMs + attackDurationMs;
        
        // 겹치지 않음
        if (endTimeMs() < attackStartMs || startTimeMs > attackEndMs) {
            return 0.0;
        }

        // 겹치는 영역
        long overlapStart = Math.max(startTimeMs, attackStartMs);
        long overlapEnd = Math.min(endTimeMs(), attackEndMs);
        long overlapDuration = overlapEnd - overlapStart;

        // 공격 시간 기준으로 겹침 비율 계산
        return Math.min(1.0, overlapDuration / (double) attackDurationMs);
    }

    /**
     * 완전 회피 여부 판정 (회피 범위가 공격 시간을 완전히 포함하면 완전 회피)
     */
    public boolean isFullEvade(long attackStartMs, long attackDurationMs) {
        long attackEndMs = attackStartMs + attackDurationMs;
        return startTimeMs <= attackStartMs && endTimeMs() >= attackEndMs;
    }
}
