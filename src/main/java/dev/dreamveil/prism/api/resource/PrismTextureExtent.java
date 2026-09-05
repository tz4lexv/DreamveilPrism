package dev.dreamveil.prism.api.resource;

/** Absolute or main-target-relative texture dimensions. */
public record PrismTextureExtent(Mode mode, int width, int height, double scaleX, double scaleY) {
    public enum Mode {
        ABSOLUTE,
        RELATIVE_TO_MAIN_TARGET
    }

    public PrismTextureExtent {
        if (mode == null) {
            throw new IllegalArgumentException("Texture extent mode must not be null");
        }
        if (mode == Mode.ABSOLUTE) {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("Absolute dimensions must be >= 1");
            }
            scaleX = 0.0;
            scaleY = 0.0;
        } else {
            if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0.0 || scaleY <= 0.0) {
                throw new IllegalArgumentException("Relative scales must be finite and > 0");
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
}
