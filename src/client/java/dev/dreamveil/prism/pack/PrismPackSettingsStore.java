package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.setting.PrismPackSetting;
import dev.dreamveil.prism.api.setting.PrismPackSettingDefinition;

/** Persists user/creator setting values outside pack source directories so hot reload inputs stay clean. */
final class PrismPackSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root;

    PrismPackSettingsStore(Path gameDir) {
        this.root = gameDir.resolve("config").resolve("dreamveil-prism").resolve("packs").toAbsolutePath().normalize();
    }

    List<PrismPackSetting> load(PrismPackDefinition pack) {
        Map<String, String> persisted = read(pack.id());
        List<PrismPackSetting> settings = new ArrayList<>(pack.settings().size());
        for (PrismPackSettingDefinition definition : pack.settings()) {
            String raw = persisted.getOrDefault(definition.id(), definition.defaultValue());
            String normalized;
            try {
                normalized = PrismPackSettingValues.normalize(definition, raw);
            } catch (PrismPackLoadException exception) {
                PrismMod.LOGGER.warn(
                        "Ignoring invalid persisted Prism setting {}.{}='{}'; using creator default '{}'",
                        pack.id(), definition.id(), raw, definition.defaultValue());
                normalized = definition.defaultValue();
            }
            settings.add(new PrismPackSetting(definition, normalized));
        }
        return List.copyOf(settings);
    }

    boolean write(PrismPackDefinition pack, List<PrismPackSetting> settings) {
        JsonObject rootObject = new JsonObject();
        for (PrismPackSetting setting : settings) {
            switch (setting.definition().type()) {
                case BOOLEAN -> rootObject.addProperty(setting.definition().id(), setting.booleanValue());
                case INTEGER -> rootObject.addProperty(setting.definition().id(), setting.integerValue());
                case FLOAT -> rootObject.addProperty(setting.definition().id(), setting.floatValue());
                case ENUM -> rootObject.addProperty(setting.definition().id(), setting.value());
            }
        }
        return writeObject(pack.id(), rootObject);
    }

    boolean reset(PrismPackDefinition pack) {
        Path file = file(pack.id());
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException exception) {
            PrismMod.LOGGER.error("Could not reset Prism settings {}", file, exception);
            return false;
        }
    }

    private Map<String, String> read(String packId) {
        Path file = file(packId);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return Map.of();
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (var entry : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()) {
                    values.put(entry.getKey(), value.getAsString());
                }
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            PrismMod.LOGGER.warn("Could not read Prism settings {}; creator defaults will be used", file, exception);
            return Map.of();
        }
    }

    private boolean writeObject(String packId, JsonObject object) {
        Path file = file(packId);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(root);
            Files.writeString(temporary, GSON.toJson(object) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            PrismMod.LOGGER.error("Could not persist Prism settings {}", file, exception);
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            return false;
        }
    }

    private Path file(String packId) {
        return root.resolve(packId + ".json");
    }
}
