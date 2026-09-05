package dev.dreamveil.prism.api;

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

/** Public API surface exposed to shader packs, creator tooling and integrations. */
public interface PrismApi {
    PrismApiVersion apiVersion();

    String prismVersion();

    String minecraftVersion();

    PrismCapabilities capabilities();

    /** API 1.24. Empty for implementations which do not report admission limits. */
    default java.util.Optional<PrismLimits> limits() { return java.util.Optional.empty(); }

    PrismFrameApi frames();

    PrismResourceApi resources();

    PrismRenderGraphApi renderGraphs();

    PrismDiagnosticsApi diagnostics();

    PrismPackApi packs();

    /** Added in Prism API 1.2; default keeps older third-party API implementations source/binary friendly. */
    default PrismPackSettingsApi settings() {
        return PrismPackSettingsApi.EMPTY;
    }

    /** Added in Prism API 1.2; CPU submission profiler, not GPU timing. */
    default PrismPerformanceApi performance() {
        return PrismPerformanceApi.EMPTY;
    }

    /** Added in Prism API 1.3; backend-neutral advanced graphics capability surface. */
    default PrismGraphicsApi graphics() {
        return PrismGraphicsApi.EMPTY;
    }

    /** Added in Prism API 1.3; optional CSM/shadow math helpers, not a built-in shadow renderer. */
    default PrismShadowApi shadows() {
        return PrismShadowApi.DEFAULT;
    }

    /** Added in Prism API 1.3; temporal history validity hints for pack-authored temporal effects. */
    default PrismTemporalApi temporal() {
        return PrismTemporalApi.EMPTY;
    }

    /** Added in Prism API 1.9; API 1.10 adds scoped Feature Rendering variant diagnostics; API 1.11 adds shadow submission/visibility status. */
    default PrismWorldRenderApi worldRendering() {
        return PrismWorldRenderApi.EMPTY;
    }
}
