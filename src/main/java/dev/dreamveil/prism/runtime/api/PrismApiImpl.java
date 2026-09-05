package dev.dreamveil.prism.runtime.api;

import dev.dreamveil.prism.api.PrismApi;
import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapabilities;
import dev.dreamveil.prism.api.diagnostics.PrismDiagnosticsApi;
import dev.dreamveil.prism.api.frame.PrismFrameApi;
import dev.dreamveil.prism.api.graph.PrismRenderGraphApi;
import dev.dreamveil.prism.api.pack.PrismPackApi;
import dev.dreamveil.prism.api.resource.PrismResourceApi;
import dev.dreamveil.prism.api.setting.PrismPackSettingsApi;
import dev.dreamveil.prism.api.performance.PrismPerformanceApi;
import dev.dreamveil.prism.api.graphics.PrismGraphicsApi;
import dev.dreamveil.prism.api.shadow.PrismShadowApi;
import dev.dreamveil.prism.api.temporal.PrismTemporalApi;
import dev.dreamveil.prism.api.render.PrismWorldRenderApi;
import dev.dreamveil.prism.backend.PrismBackendInfo;

public final class PrismApiImpl implements PrismApi {
    @Override
    public java.util.Optional<dev.dreamveil.prism.api.PrismLimits> limits() {
        return java.util.Optional.of(dev.dreamveil.prism.api.PrismLimits.LOADER);
    }
    private final String prismVersion;
    private final String minecraftVersion;
    private final PrismCapabilities capabilities;
    private final PrismFrameApiImpl frames = new PrismFrameApiImpl();
    private final PrismResourceApi resources = new PrismResourceApiImpl();
    private final PrismRenderGraphApi renderGraphs = new PrismRenderGraphApiImpl();
    private final PrismDiagnosticsApi diagnostics;
    private final PrismPackApiImpl packs = new PrismPackApiImpl();
    private final PrismPackSettingsApiImpl settings = new PrismPackSettingsApiImpl();
    private final PrismPerformanceApiImpl performance = new PrismPerformanceApiImpl();
    private final PrismGraphicsApiImpl graphics;
    private final PrismShadowApi shadows = PrismShadowApi.DEFAULT;
    private final PrismTemporalApiImpl temporal = new PrismTemporalApiImpl();
    private final PrismWorldRenderApiImpl worldRendering = new PrismWorldRenderApiImpl();

    public PrismApiImpl(
            String prismVersion,
            String minecraftVersion,
            PrismBackendInfo backendInfo) {
        this.prismVersion = prismVersion;
        this.minecraftVersion = minecraftVersion;
        this.capabilities = new PrismCapabilitiesImpl(backendInfo.capabilities());
        this.graphics = new PrismGraphicsApiImpl(backendInfo);
        this.diagnostics = new PrismDiagnosticsApiImpl(prismVersion, minecraftVersion, backendInfo, frames);
    }

    @Override
    public PrismApiVersion apiVersion() {
        return PrismApiVersion.CURRENT;
    }

    @Override
    public String prismVersion() {
        return prismVersion;
    }

    @Override
    public String minecraftVersion() {
        return minecraftVersion;
    }

    @Override
    public PrismCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public PrismFrameApi frames() {
        return frames;
    }

    @Override
    public PrismResourceApi resources() {
        return resources;
    }

    @Override
    public PrismRenderGraphApi renderGraphs() {
        return renderGraphs;
    }

    @Override
    public PrismDiagnosticsApi diagnostics() {
        return diagnostics;
    }

    @Override
    public PrismPackApi packs() {
        return packs;
    }

    @Override
    public PrismPackSettingsApi settings() {
        return settings;
    }

    @Override
    public PrismPerformanceApi performance() {
        return performance;
    }

    @Override
    public PrismGraphicsApi graphics() {
        return graphics;
    }

    @Override
    public PrismShadowApi shadows() {
        return shadows;
    }

    @Override
    public PrismTemporalApi temporal() {
        return temporal;
    }

    @Override
    public PrismWorldRenderApi worldRendering() {
        return worldRendering;
    }

    public PrismFrameApiImpl mutableFrames() {
        return frames;
    }

    public PrismPackApiImpl mutablePacks() {
        return packs;
    }

    public PrismPackSettingsApiImpl mutableSettings() {
        return settings;
    }

    public PrismPerformanceApiImpl mutablePerformance() {
        return performance;
    }

    public PrismTemporalApiImpl mutableTemporal() {
        return temporal;
    }

    public PrismWorldRenderApiImpl mutableWorldRendering() {
        return worldRendering;
    }
}
