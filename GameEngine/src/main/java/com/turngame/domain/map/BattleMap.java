package com.turngame.domain.map;

import java.util.List;
import java.util.Objects;

public record BattleMap(
		String code,
		String name,
		String description,
		int rows,
		int cols,
		List<String> layoutRows
) {
	public BattleMap {
		Objects.requireNonNull(code, "code cannot be null");
		Objects.requireNonNull(name, "name cannot be null");
		Objects.requireNonNull(description, "description cannot be null");

		if (rows <= 0 || cols <= 0) {
			throw new IllegalArgumentException("rows and cols must be positive");
		}

		if (layoutRows == null || layoutRows.isEmpty()) {
			layoutRows = defaultLayout(rows, cols);
		} else {
			if (layoutRows.size() != rows) {
				throw new IllegalArgumentException("layoutRows size must match rows");
			}
			for (String row : layoutRows) {
				if (row == null || row.length() != cols) {
					throw new IllegalArgumentException("each layout row must match cols");
				}
			}
			layoutRows = List.copyOf(layoutRows);
		}
	}

	public BattleMap(String code, String name, String description) {
		this(code, name, description, 3, 3, defaultLayout(3, 3));
	}

	public static List<String> defaultLayout(int rows, int cols) {
		return java.util.stream.IntStream.range(0, rows)
				.mapToObj(row -> ".".repeat(cols))
				.toList();
	}
}
