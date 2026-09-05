package dev.dreamveil.prism.runtime.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.dreamveil.prism.api.pack.PrismPackApi;
import dev.dreamveil.prism.api.pack.PrismPackInfo;
import dev.dreamveil.prism.api.pack.PrismPackMetadata;
import dev.dreamveil.prism.api.pack.PrismPackVariant;

/** Thread-safe public pack state; Minecraft-specific loader publishes immutable snapshots here. */
public final class PrismPackApiImpl implements PrismPackApi {
    private final AtomicReference<State> state = new AtomicReference<>(State.EMPTY);
    private volatile Runnable reloadHandler = () -> { };

    @Override
    public List<PrismPackInfo> installed() {
        return state.get().installed();
    }

    @Override
    public Optional<PrismPackInfo> active() {
        State current = state.get();
        if (current.activeId().isEmpty()) {
            return Optional.empty();
        }
        return current.installed().stream()
                .filter(pack -> pack.id().equals(current.activeId()))
                .findFirst();
    }

    @Override
    public Optional<PrismPackMetadata> metadata(String packId) {
        if (packId == null) return Optional.empty();
        return Optional.ofNullable(state.get().metadata().get(packId));
    }

    @Override
    public List<PrismPackVariant> variants(String packId) {
        if (packId == null) return List.of();
        return state.get().variants().getOrDefault(packId, List.of());
    }

    @Override
    public List<PrismPackVariant> allVariants() {
        List<PrismPackVariant> result = new ArrayList<>();
        state.get().variants().values().forEach(result::addAll);
        return List.copyOf(result);
    }

    @Override
    public long reloadGeneration() {
        return state.get().generation();
    }

    @Override
    public void requestReload() {
        reloadHandler.run();
    }

    public void publish(List<PrismPackInfo> installed, String activeId, long generation) {
        State old = state.get();
        publish(installed, activeId, generation, old.metadata(), old.variants());
    }

    public void publish(
            List<PrismPackInfo> installed,
            String activeId,
            long generation,
            Map<String, PrismPackMetadata> metadata) {
        publish(installed, activeId, generation, metadata, state.get().variants());
    }

    public void publish(
            List<PrismPackInfo> installed,
            String activeId,
            long generation,
            Map<String, PrismPackMetadata> metadata,
            Map<String, List<PrismPackVariant>> variants) {
        Objects.requireNonNull(installed, "installed");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0");
        }
        Map<String, List<PrismPackVariant>> safeVariants = freezeVariants(variants);
        state.set(new State(
                List.copyOf(installed),
                activeId == null ? "" : activeId,
                generation,
                Map.copyOf(metadata),
                safeVariants));
    }

    public void publishMetadata(Map<String, PrismPackMetadata> metadata) {
        Map<String, PrismPackMetadata> safe = Map.copyOf(metadata);
        state.updateAndGet(old -> new State(
                old.installed(), old.activeId(), old.generation(), safe, old.variants()));
    }

    public void publishVariants(Map<String, List<PrismPackVariant>> variants) {
        Map<String, List<PrismPackVariant>> safe = freezeVariants(variants);
        state.updateAndGet(old -> new State(
                old.installed(), old.activeId(), old.generation(), old.metadata(), safe));
    }

    public void setReloadHandler(Runnable handler) {
        reloadHandler = Objects.requireNonNull(handler, "handler");
    }

    public void clear() {
        reloadHandler = () -> { };
        state.set(State.EMPTY);
    }

    private static Map<String, List<PrismPackVariant>> freezeVariants(Map<String, List<PrismPackVariant>> variants) {
        if (variants == null || variants.isEmpty()) return Map.of();
        java.util.LinkedHashMap<String, List<PrismPackVariant>> result = new java.util.LinkedHashMap<>();
        variants.forEach((id, values) -> result.put(id, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private record State(
            List<PrismPackInfo> installed,
            String activeId,
            long generation,
            Map<String, PrismPackMetadata> metadata,
            Map<String, List<PrismPackVariant>> variants) {
        private static final State EMPTY = new State(List.of(), "", 0L, Map.of(), Map.of());
    }
}
