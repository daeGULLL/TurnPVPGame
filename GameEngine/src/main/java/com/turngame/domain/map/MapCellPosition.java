package com.turngame.domain.map;

public record MapCellPosition(int col, int row) {
    public int manhattanDistanceTo(MapCellPosition other) {
        return Math.abs(col - other.col) + Math.abs(row - other.row);
    }

    public int chebyshevDistanceTo(MapCellPosition other) {
        return Math.max(Math.abs(col - other.col), Math.abs(row - other.row));
    }
}
