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
	public static final char TILE_EMPTY = '.';
	public static final char TILE_BLOCKED = '#';
	public static final char TILE_PILLAR = 'P';
	public static final char TILE_SLOW = 'S';

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

	public char tileAt(int col, int row) {
		if (row < 0 || row >= rows || col < 0 || col >= cols) {
			return TILE_EMPTY;
		}
		return layoutRows.get(row).charAt(col);
	}

	public boolean isBlockedCell(int col, int row) {
		char tile = tileAt(col, row);
		return tile == TILE_BLOCKED || tile == TILE_PILLAR;
	}

	public boolean isSlowCell(int col, int row) {
		return tileAt(col, row) == TILE_SLOW;
	}

	public boolean isPassableCell(int col, int row) {
		return !isBlockedCell(col, row);
	}
}
