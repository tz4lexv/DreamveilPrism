package dev.dreamveil.prism.api.frame;

import java.util.Arrays;

/** Immutable column-major 4x4 matrix without a dependency on Minecraft or JOML. */
public final class PrismMatrix4 {
    private static final int ELEMENT_COUNT = 16;
    private final float[] values;

    public PrismMatrix4(float[] columnMajorValues) {
        if (columnMajorValues == null || columnMajorValues.length != ELEMENT_COUNT) {
            throw new IllegalArgumentException("PrismMatrix4 requires exactly 16 values");
        }
        this.values = columnMajorValues.clone();
    }

    public static PrismMatrix4 identity() {
        return new PrismMatrix4(new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        });
    }

    public float get(int column, int row) {
        if (column < 0 || column > 3 || row < 0 || row > 3) {
            throw new IndexOutOfBoundsException("Matrix indices must be in [0, 3]");
        }
        return values[column * 4 + row];
    }

    public float[] toArray() {
        return values.clone();
    }

    /** Matrix product {@code this * right}, preserving Prism's column-major storage contract. */
    public PrismMatrix4 multiply(PrismMatrix4 right) {
        if (right == null) {
            throw new IllegalArgumentException("right matrix must not be null");
        }
        float[] result = new float[ELEMENT_COUNT];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0.0;
                for (int k = 0; k < 4; k++) {
                    value += (double) get(k, row) * right.get(column, k);
                }
                result[column * 4 + row] = (float) value;
            }
        }
        return new PrismMatrix4(result);
    }

    public boolean approximatelyEquals(PrismMatrix4 other, float epsilon) {
        if (other == null || !Float.isFinite(epsilon) || epsilon < 0.0f) {
            return false;
        }
        for (int index = 0; index < ELEMENT_COUNT; index++) {
            if (Math.abs(values[index] - other.values[index]) > epsilon) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PrismMatrix4 other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "PrismMatrix4" + Arrays.toString(values);
    }
}
