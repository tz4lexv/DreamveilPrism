package dev.dreamveil.prism.api.shadow;

import java.util.List;

/** Atlas packing helper only; Prism does not impose atlas-vs-array shadow storage. */
public record PrismShadowAtlasLayout(int width, int height, List<PrismShadowAtlasTile> tiles) {
    public PrismShadowAtlasLayout {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Atlas dimensions must be >= 1");
        tiles = List.copyOf(tiles == null ? List.of() : tiles);
        for (PrismShadowAtlasTile tile : tiles) {
            if ((long) tile.x() + tile.width() > width || (long) tile.y() + tile.height() > height) {
                throw new IllegalArgumentException("Shadow atlas tile exceeds atlas bounds");
            }
        }
    }
}
