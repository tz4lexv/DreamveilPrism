package dev.dreamveil.prism.api.setting;

import java.util.List;
import java.util.Optional;

/** Public pack-settings surface. Prism persists values; packs decide what the values mean visually. */
public interface PrismPackSettingsApi {
    PrismPackSettingsApi EMPTY = new PrismPackSettingsApi() {
        @Override public List<PrismPackSetting> settings(String packId) { return List.of(); }
        @Override public Optional<PrismPackSetting> setting(String packId, String settingId) { return Optional.empty(); }
        @Override public boolean set(String packId, String settingId, String value) { return false; }
        @Override public boolean reset(String packId) { return false; }
    };

    List<PrismPackSetting> settings(String packId);

    Optional<PrismPackSetting> setting(String packId, String settingId);

    /** Persists and schedules a safe recompile when the value is valid and changed. */
    boolean set(String packId, String settingId, String value);

    /** Restores creator defaults for this pack and schedules a safe recompile. */
    boolean reset(String packId);
}
