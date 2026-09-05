package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.dreamveil.prism.PrismMod;
import net.fabricmc.loader.api.FabricLoader;

/** Small persisted loader policy; every exposed value is consumed by a concrete render path. */
public final class PrismClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final PrismClientConfig INSTANCE = load();

    private final Path path;
    private boolean builtInShadows;
    private String shadowQuality;
    private boolean shaderHotReload;
    private boolean dynamicResolution;
    private boolean gpuProfiling;
    private int featureWarmupPerFrame;

    private PrismClientConfig(Path path) {
        this.path = path;
    }

    public static PrismClientConfig get() {
        return INSTANCE;
    }

    public synchronized boolean builtInShadows() { return builtInShadows; }
    public synchronized String shadowQuality() { return shadowQuality; }
    public synchronized boolean shaderHotReload() { return shaderHotReload; }
    public synchronized boolean dynamicResolution() { return dynamicResolution; }
    public synchronized boolean gpuProfiling() { return gpuProfiling; }
    public synchronized int featureWarmupPerFrame() { return featureWarmupPerFrame; }

    public synchronized void setBuiltInShadows(boolean value) { builtInShadows = value; save(); }
    public synchronized void setShadowQuality(String value) {
        shadowQuality = "quality".equalsIgnoreCase(value) ? "quality" : "balanced";
        save();
    }
    public synchronized void setShaderHotReload(boolean value) { shaderHotReload = value; save(); }
    public synchronized void setDynamicResolution(boolean value) { dynamicResolution = value; save(); }
    public synchronized void setGpuProfiling(boolean value) { gpuProfiling = value; save(); }
    public synchronized void setFeatureWarmupPerFrame(int value) {
        featureWarmupPerFrame = Math.clamp(value, 1, 8);
        save();
    }

    private static PrismClientConfig load() {
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path path = gameDir.resolve("config").resolve("dreamveil-prism.json");
        PrismClientConfig config = new PrismClientConfig(path);
        // Preserve the alpha bring-up flag as a one-time migration for existing test installs.
        config.builtInShadows = Files.exists(gameDir.resolve("prism-shadow-depth-debug.enabled"));
        config.shadowQuality = System.getProperty("dreamveil.prism.shadowQuality", "balanced");
        config.shaderHotReload = true;
        config.dynamicResolution = true;
        config.gpuProfiling = true;
        config.featureWarmupPerFrame = 1;
        if (!Files.isRegularFile(path)) return config;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("built_in_shadows")) config.builtInShadows = root.get("built_in_shadows").getAsBoolean();
            if (root.has("shadow_quality")) config.shadowQuality = root.get("shadow_quality").getAsString();
            if (root.has("shader_hot_reload")) config.shaderHotReload = root.get("shader_hot_reload").getAsBoolean();
            if (root.has("dynamic_resolution")) config.dynamicResolution = root.get("dynamic_resolution").getAsBoolean();
            if (root.has("gpu_profiling")) config.gpuProfiling = root.get("gpu_profiling").getAsBoolean();
            if (root.has("feature_warmup_per_frame")) {
                config.featureWarmupPerFrame = Math.clamp(root.get("feature_warmup_per_frame").getAsInt(), 1, 8);
            }
            config.shadowQuality = "quality".equalsIgnoreCase(config.shadowQuality) ? "quality" : "balanced";
        } catch (RuntimeException | IOException exception) {
            PrismMod.LOGGER.warn("Could not read {}; using safe Prism defaults", path, exception);
        }
        return config;
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("built_in_shadows", builtInShadows);
        root.addProperty("shadow_quality", shadowQuality);
        root.addProperty("shader_hot_reload", shaderHotReload);
        root.addProperty("dynamic_resolution", dynamicResolution);
        root.addProperty("gpu_profiling", gpuProfiling);
        root.addProperty("feature_warmup_per_frame", featureWarmupPerFrame);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            PrismMod.LOGGER.error("Could not persist Prism client configuration {}", path, exception);
        }
    }
}
