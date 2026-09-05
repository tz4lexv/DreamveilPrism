package dev.dreamveil.prism.api;

/** Semantic version for the stable creator-facing Prism API, independent from the mod version. */
public record PrismApiVersion(int major, int minor) implements Comparable<PrismApiVersion> {
    public static final PrismApiVersion V1_0 = new PrismApiVersion(1, 0);
    public static final PrismApiVersion V1_1 = new PrismApiVersion(1, 1);
    /** Compatibility alias for the original Prism API 1.0 release. */
    public static final PrismApiVersion V1 = V1_0;
    public static final PrismApiVersion V1_2 = new PrismApiVersion(1, 2);
    public static final PrismApiVersion V1_3 = new PrismApiVersion(1, 3);
    public static final PrismApiVersion V1_4 = new PrismApiVersion(1, 4);
    public static final PrismApiVersion V1_5 = new PrismApiVersion(1, 5);
    public static final PrismApiVersion V1_6 = new PrismApiVersion(1, 6);
    public static final PrismApiVersion V1_7 = new PrismApiVersion(1, 7);
    public static final PrismApiVersion V1_8 = new PrismApiVersion(1, 8);
    public static final PrismApiVersion V1_9 = new PrismApiVersion(1, 9);
    public static final PrismApiVersion V1_10 = new PrismApiVersion(1, 10);
    public static final PrismApiVersion V1_11 = new PrismApiVersion(1, 11);
    public static final PrismApiVersion V1_12 = new PrismApiVersion(1, 12);
    public static final PrismApiVersion V1_13 = new PrismApiVersion(1, 13);
    public static final PrismApiVersion V1_14 = new PrismApiVersion(1, 14);
    public static final PrismApiVersion V1_15 = new PrismApiVersion(1, 15);
    public static final PrismApiVersion V1_16 = new PrismApiVersion(1, 16);
    public static final PrismApiVersion V1_17 = new PrismApiVersion(1, 17);
    public static final PrismApiVersion V1_18 = new PrismApiVersion(1, 18);
    public static final PrismApiVersion V1_19 = new PrismApiVersion(1, 19);
    public static final PrismApiVersion V1_20 = new PrismApiVersion(1, 20);
    public static final PrismApiVersion V1_21 = new PrismApiVersion(1, 21);
    public static final PrismApiVersion V1_22 = new PrismApiVersion(1, 22);
    public static final PrismApiVersion V1_23 = new PrismApiVersion(1, 23);
    public static final PrismApiVersion V1_24 = new PrismApiVersion(1, 24);
    public static final PrismApiVersion V1_25 = new PrismApiVersion(1, 25);
    public static final PrismApiVersion CURRENT = V1_25;

    public PrismApiVersion {
        if (major < 1) {
            throw new IllegalArgumentException("Prism API major version must be >= 1");
        }
        if (minor < 0) {
            throw new IllegalArgumentException("Prism API minor version must be >= 0");
        }
    }


    public static PrismApiVersion parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Prism API version must not be blank");
        }
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Prism API version must use <major>.<minor>: " + value);
        }
        try {
            return new PrismApiVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Prism API version: " + value, exception);
        }
    }

    public boolean isCompatibleWith(PrismApiVersion required) {
        if (required == null) {
            return false;
        }
        return major == required.major && minor >= required.minor;
    }

    @Override
    public int compareTo(PrismApiVersion other) {
        int majorCompare = Integer.compare(major, other.major);
        return majorCompare != 0 ? majorCompare : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
