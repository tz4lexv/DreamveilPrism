package dev.dreamveil.prism.pack;

import java.util.List;

record PrismPackTextureAssetDefinition(
        String id,
        List<String> sources,
        String dimension,
        String colorSpace,
        int width,
        int height,
        int mipLevels) {
    PrismPackTextureAssetDefinition {
        if (id == null || id.isBlank() || sources == null || sources.isEmpty()
                || sources.stream().anyMatch(source -> source == null || source.isBlank())) {
            throw new IllegalArgumentException("Pack texture asset id/sources must not be blank");
        }
        sources = List.copyOf(sources);
        dimension = dimension == null || dimension.isBlank() ? "2d" : dimension;
        colorSpace = colorSpace == null || colorSpace.isBlank() ? "linear" : colorSpace;
        if (width < 1 || height < 1 || mipLevels < 1) {
            throw new IllegalArgumentException("Pack texture asset dimensions/mips must be positive");
        }
        if (("cube".equals(dimension) && sources.size() != 6)
                || ("2d".equals(dimension) && sources.size() != 1)
                || (("2d_array".equals(dimension) || "3d".equals(dimension)) && sources.size() < 2)) {
            throw new IllegalArgumentException(
                    "2D textures require one source, cubemaps six faces, and arrays/volumes at least two layers");
        }
    }

    boolean srgb() {
        return "srgb".equals(colorSpace);
    }

    boolean cubemap() {
        return "cube".equals(dimension);
    }

    boolean array() {
        return "2d_array".equals(dimension);
    }

    boolean volume() {
        return "3d".equals(dimension);
    }
}
