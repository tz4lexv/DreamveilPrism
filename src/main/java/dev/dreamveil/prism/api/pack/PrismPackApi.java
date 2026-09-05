package dev.dreamveil.prism.api.pack;

import java.util.List;
import java.util.Optional;

/** Public read/control surface for creator shader packs. */
public interface PrismPackApi {
    List<PrismPackInfo> installed();

    Optional<PrismPackInfo> active();

    long reloadGeneration();

    /** Added in API 1.3. Preferred-version metadata for compatibility callers. */
    default Optional<PrismPackMetadata> metadata(String packId) {
        return Optional.empty();
    }

    /**
     * Added in API 1.4. Returns every discovered valid version/source for a pack id.
     * The compatibility installed() list still exposes at most one preferred version per id.
     */
    default List<PrismPackVariant> variants(String packId) {
        return List.of();
    }

    /** Added in API 1.4. Flattened view of all discovered valid pack versions. */
    default List<PrismPackVariant> allVariants() {
        return List.of();
    }

    /** Requests an asynchronous rescan/recompile at the next safe render-thread point. */
    void requestReload();
}
