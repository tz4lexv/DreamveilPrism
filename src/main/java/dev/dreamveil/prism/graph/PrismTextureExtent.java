package dev.dreamveil.prism.graph;

/**
 * Texture dimensions expressed either absolutely or relative to the host main
 * color target. Relative extents make render-graph resources resize-safe.
 */
public record PrismTextureExtent(Mode mode, int width, int height, double scaleX, double scaleY) {
    public enum Mode {
        ABSOLUTE,
        RELATIVE_TO_MAIN_TARGET,
        DYNAMIC_RELATIVE_TO_MAIN_TARGET
    }

    public PrismTextureExtent {
        if (mode == null) {
            throw new IllegalArgumentException("Texture extent mode must not be null");
        }
        if (mode == Mode.ABSOLUTE) {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("Absolute texture dimensions must be >= 1");
            }
            scaleX = 0.0;
            scaleY = 0.0;
        } else {
            if (!(scaleX > 0.0) || !(scaleY > 0.0) || !Double.isFinite(scaleX) || !Double.isFinite(scaleY)) {
                throw new IllegalArgumentException("Relative texture scales must be finite and > 0");
            }
            width = 0;
            height = 0;
        }
    }

    public static PrismTextureExtent absolute(int width, int height) {
        return new PrismTextureExtent(Mode.ABSOLUTE, width, height, 0.0, 0.0);
    }

    public static PrismTextureExtent relative(double scale) {
        return relative(scale, scale);
    }

    public static PrismTextureExtent relative(double scaleX, double scaleY) {
        return new PrismTextureExtent(Mode.RELATIVE_TO_MAIN_TARGET, 0, 0, scaleX, scaleY);
    }

    public static PrismTextureExtent dynamicRelative(double scale) {
        return dynamicRelative(scale, scale);
    }

    public static PrismTextureExtent dynamicRelative(double scaleX, double scaleY) {
        return new PrismTextureExtent(Mode.DYNAMIC_RELATIVE_TO_MAIN_TARGET, 0, 0, scaleX, scaleY);
    }

    public int resolveWidth(int mainWidth) {
        if (mainWidth < 1) {
            throw new IllegalArgumentException("Main target width must be >= 1");
        }
        return resolveWidth(mainWidth, 1.0);
    }

    public int resolveHeight(int mainHeight) {
        if (mainHeight < 1) {
            throw new IllegalArgumentException("Main target height must be >= 1");
        }
        return resolveHeight(mainHeight, 1.0);
    }

    public int resolveWidth(int mainWidth, double dynamicScale) {
        if (mainWidth < 1) throw new IllegalArgumentException("Main target width must be >= 1");
        return mode == Mode.ABSOLUTE ? width
                : resolveScaled(mainWidth, scaleX * (mode == Mode.DYNAMIC_RELATIVE_TO_MAIN_TARGET
                        ? requireDynamicScale(dynamicScale) : 1.0), "width");
    }

    public int resolveHeight(int mainHeight, double dynamicScale) {
        if (mainHeight < 1) throw new IllegalArgumentException("Main target height must be >= 1");
        return mode == Mode.ABSOLUTE ? height
                : resolveScaled(mainHeight, scaleY * (mode == Mode.DYNAMIC_RELATIVE_TO_MAIN_TARGET
                        ? requireDynamicScale(dynamicScale) : 1.0), "height");
    }

    public boolean dynamic() {
        return mode == Mode.DYNAMIC_RELATIVE_TO_MAIN_TARGET;
    }

    private static double requireDynamicScale(double scale) {
        if (!Double.isFinite(scale) || scale <= 0.0 || scale > 1.0) {
            throw new IllegalArgumentException("Dynamic resolution scale must be finite and within (0,1]");
        }
        return scale;
    }

    private static int resolveScaled(int base, double scale, String axis) {
        double scaled = base * scale;
        if (!Double.isFinite(scaled) || scaled > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Resolved texture " + axis + " exceeds supported integer range");
        }
        long rounded = Math.round(scaled);
        return (int) Math.max(1L, rounded);
    }
}
