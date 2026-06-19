package com.turngame.domain.skill;

import java.util.List;

/**
 * 스킬의 맵 영향 범위와 시간 효과를 정의합니다.
 * 동적(Dynamic): 시전자 위치 기반으로 매 턴 확산
 * 정적(Static): 시전 시점에 고정된 영역
 *
 * areaPatternRows를 지정하면 STATIC 스킬은 반지름 대신 패턴 기반으로 범위를 판정합니다.
 * 패턴 문자는 'X'(영향 칸), 'C'(시전자 기준점), '.'(빈 칸)을 권장합니다.
 */
public record SkillEffect(
        AreaType areaType,
        int areaRadius,
    int durationTurns,
    List<String> areaPatternRows
) {
    public enum AreaType {
        STATIC,    // 시전 시점에 고정 범위
        DYNAMIC    // 시전자 위치 기반 동적 확산
    }

    public SkillEffect {
        if (areaType == null) {
            throw new IllegalArgumentException("areaType must not be null");
        }
        if (areaRadius < 0 || durationTurns < 0) {
            throw new IllegalArgumentException("areaRadius and durationTurns must be non-negative");
        }
        areaPatternRows = normalizePatternRows(areaPatternRows);
        validatePatternRows(areaPatternRows);
    }

    public SkillEffect(AreaType areaType, int areaRadius, int durationTurns) {
        this(areaType, areaRadius, durationTurns, List.of());
    }

    public static SkillEffect staticPattern(List<String> areaPatternRows, int durationTurns) {
        return new SkillEffect(AreaType.STATIC, deriveRadius(areaPatternRows), durationTurns, areaPatternRows);
    }

    public boolean hasAreaPattern() {
        return areaType == AreaType.STATIC && areaPatternRows != null && !areaPatternRows.isEmpty();
    }

    public boolean includesOffset(int deltaCol, int deltaRow) {
        if (!hasAreaPattern()) {
            return Math.abs(deltaCol) + Math.abs(deltaRow) <= areaRadius;
        }

        int[] center = resolveCenter(areaPatternRows);
        int centerCol = center[0];
        int centerRow = center[1];
        for (int row = 0; row < areaPatternRows.size(); row++) {
            String rowText = areaPatternRows.get(row);
            for (int col = 0; col < rowText.length(); col++) {
                if (!isFilledCell(rowText.charAt(col))) {
                    continue;
                }
                if ((col - centerCol) == deltaCol && (row - centerRow) == deltaRow) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> normalizePatternRows(List<String> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> row == null ? "" : row).toList();
    }

    private static void validatePatternRows(List<String> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int width = rows.get(0).length();
        if (width == 0) {
            throw new IllegalArgumentException("areaPatternRows must not contain empty rows");
        }
        boolean hasFilledCell = false;
        for (String row : rows) {
            if (row.length() != width) {
                throw new IllegalArgumentException("areaPatternRows must be a rectangular grid");
            }
            for (int i = 0; i < row.length(); i++) {
                char ch = row.charAt(i);
                if (!isPatternCell(ch)) {
                    throw new IllegalArgumentException("areaPatternRows supports only 'X', 'C', '.' characters");
                }
                if (isFilledCell(ch)) {
                    hasFilledCell = true;
                }
            }
        }
        if (!hasFilledCell) {
            throw new IllegalArgumentException("areaPatternRows must include at least one affected cell");
        }
    }

    private static int[] resolveCenter(List<String> rows) {
        int centerCol = rows.get(0).length() / 2;
        int centerRow = rows.size() / 2;
        for (int row = 0; row < rows.size(); row++) {
            String rowText = rows.get(row);
            for (int col = 0; col < rowText.length(); col++) {
                char ch = rowText.charAt(col);
                if (ch == 'C' || ch == 'c') {
                    return new int[]{col, row};
                }
            }
        }
        return new int[]{centerCol, centerRow};
    }

    private static int deriveRadius(List<String> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        validatePatternRows(rows);
        int[] center = resolveCenter(rows);
        int max = 0;
        for (int row = 0; row < rows.size(); row++) {
            String rowText = rows.get(row);
            for (int col = 0; col < rowText.length(); col++) {
                if (!isFilledCell(rowText.charAt(col))) {
                    continue;
                }
                int distance = Math.abs(col - center[0]) + Math.abs(row - center[1]);
                max = Math.max(max, distance);
            }
        }
        return max;
    }

    private static boolean isPatternCell(char ch) {
        return ch == 'X' || ch == 'x' || ch == 'C' || ch == 'c' || ch == '.';
    }

    private static boolean isFilledCell(char ch) {
        return ch == 'X' || ch == 'x' || ch == 'C' || ch == 'c';
    }
}
