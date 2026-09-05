package dev.dreamveil.prism.runtime.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.dreamveil.prism.api.setting.PrismPackSetting;
import dev.dreamveil.prism.api.setting.PrismPackSettingsApi;

/** Thread-safe public snapshot/control bridge for creator pack settings. */
public final class PrismPackSettingsApiImpl implements PrismPackSettingsApi {
    @FunctionalInterface
    public interface SetHandler {
        boolean set(String packId, String settingId, String value);
    }

    @FunctionalInterface
    public interface ResetHandler {
        boolean reset(String packId);
    }

    private final AtomicReference<Map<String, List<PrismPackSetting>>> state = new AtomicReference<>(Map.of());
    private volatile SetHandler setHandler = (packId, settingId, value) -> false;
    private volatile ResetHandler resetHandler = packId -> false;

    @Override
    public List<PrismPackSetting> settings(String packId) {
        if (packId == null) {
            return List.of();
        }
        return state.get().getOrDefault(packId, List.of());
    }

    @Override
    public Optional<PrismPackSetting> setting(String packId, String settingId) {
        if (settingId == null) {
            return Optional.empty();
        }
        return settings(packId).stream()
                .filter(setting -> setting.definition().id().equals(settingId))
                .findFirst();
    }

    @Override
    public boolean set(String packId, String settingId, String value) {
        return setHandler.set(packId, settingId, value);
    }

    @Override
    public boolean reset(String packId) {
        return resetHandler.reset(packId);
    }

    public void publish(Map<String, List<PrismPackSetting>> settingsByPack) {
        Objects.requireNonNull(settingsByPack, "settingsByPack");
        Map<String, List<PrismPackSetting>> copy = new LinkedHashMap<>();
        settingsByPack.forEach((id, settings) -> copy.put(id, List.copyOf(settings)));
        state.set(Map.copyOf(copy));
    }

    public void setHandlers(SetHandler setHandler, ResetHandler resetHandler) {
        this.setHandler = Objects.requireNonNull(setHandler, "setHandler");
        this.resetHandler = Objects.requireNonNull(resetHandler, "resetHandler");
    }

    public void clear() {
        state.set(Map.of());
        setHandler = (packId, settingId, value) -> false;
        resetHandler = packId -> false;
    }
}
