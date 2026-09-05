package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.client.PrismChatReporter;
import dev.dreamveil.prism.api.pack.PrismDiagnosticSeverity;
import dev.dreamveil.prism.api.pack.PrismPackDiagnostic;
import dev.dreamveil.prism.api.pack.PrismPackInfo;
import dev.dreamveil.prism.api.pack.PrismPackMetadata;
import dev.dreamveil.prism.api.pack.PrismPackStatus;
import dev.dreamveil.prism.api.pack.PrismPackVariant;
import dev.dreamveil.prism.api.pack.PrismPackSelector;
import dev.dreamveil.prism.api.pack.PrismPackVersion;
import dev.dreamveil.prism.api.setting.PrismPackSetting;
import dev.dreamveil.prism.api.setting.PrismPackSettingDefinition;
import dev.dreamveil.prism.bridge.Blaze3DFrameResources;
import dev.dreamveil.prism.bridge.Blaze3DHistoryTextureStore;
import dev.dreamveil.prism.bridge.Blaze3DPackTextureStore;
import dev.dreamveil.prism.bridge.Blaze3DPrismBackend;
import dev.dreamveil.prism.graph.PrismHostResources;
import dev.dreamveil.prism.runtime.api.PrismPackApiImpl;
import dev.dreamveil.prism.runtime.api.PrismPackSettingsApiImpl;
import dev.dreamveil.prism.runtime.api.PrismPerformanceApiImpl;
import dev.dreamveil.prism.runtime.api.PrismWorldRenderApiImpl;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import dev.dreamveil.prism.render.PrismProjectionJitter;

/**
 * Creator shader-pack runtime for Prism.
 *
 * Pack filesystem notifications are blocking/event-driven on a daemon thread. Shader IO,
 * include expansion, validation and analysis run on a bounded preprocessing pool; Blaze3D
 * pipeline creation/renderer mutation remain on the render callback and are warm-started
 * incrementally. A failed hot reload keeps the previous compiled pack alive.
 */
public final class PrismShaderPackManager implements AutoCloseable {
    private static final int HOT_RELOAD_CACHE_WARNING = 64;
    private static final int PREPROCESS_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    private static final String DISABLED_SELECTOR = "none";
    private static final long SETTINGS_RELOAD_DEBOUNCE_NANOS = 250_000_000L;
    private static final long LIBRARY_STABLE_NANOS = 350_000_000L;
    private static final int MAX_TRANSIENT_DISCOVERY_RETRIES = 5;
    private static final int MAX_FINGERPRINT_FILES = 16_384;
    private static final long MAX_FINGERPRINT_FILE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_FINGERPRINT_TOTAL_BYTES = 160L * 1024L * 1024L;

    private final Blaze3DPrismBackend backend;
    private final PrismPackApiImpl publicApi;
    private final PrismPackSettingsApiImpl settingsApi;
    private final PrismPerformanceApiImpl performanceApi;
    private final PrismWorldRenderApiImpl worldRenderApi;
    private final Path gameDir;
    private final Path packsRoot;
    private final Path legacyPacksRoot;
    private final PrismZipPackCache zipCache;
    private final PrismPackSettingsStore settingsStore;
    private final Map<WatchKey, Path> watchDirectories = new ConcurrentHashMap<>();
    private final PrismGpuProfiler gpuProfiler = new PrismGpuProfiler();
    private final PrismPipelineSessionCache pipelineSessionCache = new PrismPipelineSessionCache();
    private final PrismPackFrameUniforms packFrameUniforms = new PrismPackFrameUniforms();
    private final Blaze3DHistoryTextureStore packHistoryTextures = new Blaze3DHistoryTextureStore();
    private final Blaze3DPackTextureStore packTextureAssets = new Blaze3DPackTextureStore();
    private final PrismHierarchicalDepthPyramid sceneHierarchicalDepth = new PrismHierarchicalDepthPyramid();
    private final PrismDynamicResolutionController dynamicResolution = new PrismDynamicResolutionController();

    private ExecutorService watcher;
    private ExecutorService preprocessor;
    private WatchService watchService;
    private volatile boolean reloadRequested = true;
    private volatile long watchReloadNotBeforeNanos;
    private volatile long settingsReloadNotBeforeNanos;
    private volatile long libraryRevision;
    private volatile boolean closed;
    private volatile boolean libraryDirty;
    private volatile boolean shaderResourceReloadInProgress;
    private volatile String pendingSelectionOverride;
    private long lastLibraryMetadataFingerprint = Long.MIN_VALUE;
    private long libraryStableSinceNanos;
    private int transientDiscoveryRetryCount;

    private PrismCompiledPack activePack;
    private GpuDevice activeDevice;
    private GpuFormat activeOutputFormat;
    private final Map<String, GpuSampler> sharedSamplers = new HashMap<>();
    private long reloadGeneration;
    private long successfulCompiles;
    private GpuTextureView lastObservedDepthView;
    private FailedCompile failedCompile;
    private PendingReload pendingReload;
    private volatile String configuredPackId = "";
    private volatile String configuredPackSelector = "";
    private boolean packResolutionChangedThisFrame;

    public PrismShaderPackManager(
            Blaze3DPrismBackend backend,
            PrismPackApiImpl publicApi,
            PrismPackSettingsApiImpl settingsApi,
            PrismPerformanceApiImpl performanceApi,
            PrismWorldRenderApiImpl worldRenderApi) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.publicApi = java.util.Objects.requireNonNull(publicApi, "publicApi");
        this.settingsApi = java.util.Objects.requireNonNull(settingsApi, "settingsApi");
        this.performanceApi = java.util.Objects.requireNonNull(performanceApi, "performanceApi");
        this.worldRenderApi = java.util.Objects.requireNonNull(worldRenderApi, "worldRenderApi");
        this.gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        this.packsRoot = gameDir.resolve("shaderpacks").toAbsolutePath().normalize();
        this.legacyPacksRoot = gameDir.resolve("prism-packs").toAbsolutePath().normalize();
        this.zipCache = new PrismZipPackCache(gameDir);
        this.settingsStore = new PrismPackSettingsStore(gameDir);
    }

    public void initialize() {
        if (closed || watcher != null) {
            return;
        }
        try {
            Files.createDirectories(packsRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Prism pack directory " + packsRoot, exception);
        }

        // Capture Minecraft's immutable chunk pipeline contracts before any Prism scene generation
        // becomes active. These templates preserve the backend-neutral Blaze3D render state while
        // allowing pack-authored shader stages to replace vanilla terrain shading.
        PrismWorldRenderingPipeline.initialize(worldRenderApi, performanceApi, this::render);

        publicApi.setReloadHandler(this::requestReload);
        settingsApi.setHandlers(this::setSetting, this::resetSettings);
        preprocessor = Executors.newFixedThreadPool(PREPROCESS_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "Dreamveil-Prism-Shader-Preprocess");
            thread.setDaemon(true);
            return thread;
        });
        reloadRequested = false;
        publishDiscoverySnapshot();
        reloadRequested = reloadRequested || !configuredPackId.isEmpty();
        startWatcher();
        // END_MAIN composite execution is owned by PrismWorldRenderingPipeline so scene-phase
        // publication and post/composite scheduling cannot drift into two independent event paths.

        PrismMod.LOGGER.info(
                "Prism shader-pack runtime v{} initialized; Shader Library 2='{}', unified WorldRenderingPipeline + semantic ProgramSet + final presentation composites, preprocessThreads={}, warmupBudget={} pipeline/frame, transactional activation enabled",
                PrismMod.VERSION, packsRoot, PREPROCESS_THREADS,
                PrismClientConfig.get().featureWarmupPerFrame());
    }

    private void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerWatchTree(packsRoot);
            if (Files.isDirectory(legacyPacksRoot)) registerWatchTree(legacyPacksRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start Prism pack filesystem watcher", exception);
        }

        watcher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Dreamveil-Prism-Pack-Watcher");
            thread.setDaemon(true);
            return thread;
        });
        watcher.execute(this::watchLoop);
    }

    private void watchLoop() {
        while (!closed) {
            final WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException closedService) {
                return;
            }

            Path directory = watchDirectories.get(key);
            boolean changed = false;
            if (directory != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        changed = true;
                        continue;
                    }
                    Object context = event.context();
                    if (!(context instanceof Path relative)) {
                        continue;
                    }
                    Path affected = directory.resolve(relative).normalize();
                    if (isInternalPackPath(affected)) {
                        continue;
                    }
                    String fileName = affected.getFileName() == null ? "" : affected.getFileName().toString();
                    // active.txt is Prism's own atomic selection control file. Treating our own write as
                    // a pack-content change would cause a redundant compile generation after every successful activation.
                    if (fileName.equals("active.txt") || fileName.equals("active.txt.tmp")) {
                        continue;
                    }
                    changed = true;
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(affected)) {
                        try {
                            registerWatchTree(affected);
                        } catch (IOException exception) {
                            PrismMod.LOGGER.debug("Could not watch new Prism pack directory {}", affected, exception);
                        }
                    }
                }
            }

            if (!key.reset()) {
                watchDirectories.remove(key);
            }
            if (changed) {
                if (!PrismClientConfig.get().shaderHotReload()) {
                    // Discovery UI may still rescan explicitly; only automatic compilation is off.
                    libraryRevision++;
                    libraryDirty = true;
                    continue;
                }
                // Coalesce bursty CREATE/MODIFY events. This avoids treating a ZIP that is still being copied as corrupt.
                watchReloadNotBeforeNanos = System.nanoTime() + 300_000_000L;
                libraryRevision++;
                libraryDirty = true;
                reloadRequested = true;
            }
        }
    }

    private void registerWatchTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path directory : stream
                    .filter(Files::isDirectory)
                    .filter(path -> !isInternalPackPath(path))
                    .toList()) {
                WatchKey key = directory.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                watchDirectories.put(key, directory);
            }
        }
    }

    private boolean isInternalPackPath(Path path) {
        return isInternalPackPath(packsRoot, path)
                || (Files.isDirectory(legacyPacksRoot) && isInternalPackPath(legacyPacksRoot, path));
    }

    private static boolean isInternalPackPath(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) return false;
        Path relative = normalizedRoot.relativize(normalizedPath);
        if (relative.getNameCount() == 0) return false;
        String first = relative.getName(0).toString();
        return first.equals(".prism-cache")
                || first.equals("active.txt")
                || first.equals("active.txt.tmp");
    }

    private void render(LevelRenderContext context, GpuTextureView presentedColor, CommandEncoder commandEncoder) {
        PrismChatReporter.flushPending();
        if (closed || shaderResourceReloadInProgress) {
            return;
        }

        long now = System.nanoTime();
        long reloadNotBeforeNanos = Math.max(watchReloadNotBeforeNanos, settingsReloadNotBeforeNanos);
        boolean reloadReady = (!reloadRequested
                || reloadNotBeforeNanos == 0L
                || now >= reloadNotBeforeNanos)
                && libraryStableForReload(now);

        // Minimal Off path: filesystem changes may refresh discovery/settings after the debounce,
        // but no Minecraft render target/device lookup is performed while no pack is active/selected.
        if (activePack == null && configuredPackId.isEmpty()) {
            if (reloadRequested && reloadReady) {
                reloadRequested = false;
                watchReloadNotBeforeNanos = 0L;
                settingsReloadNotBeforeNanos = 0L;
                publishDiscoverySnapshot();
            }
            if (configuredPackId.isEmpty()) {
                return;
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return;
        }

        Blaze3DFrameResources resources = Blaze3DFrameResources.fromMainTarget(
                minecraft.gameRenderer.mainRenderTarget());
        if (presentedColor != null) {
            resources.bindTexture(PrismHostResources.MAIN_COLOR, presentedColor);
        }
        GpuTextureView mainColor = resources.requireTexture(PrismHostResources.MAIN_COLOR);
        GpuDevice device = RenderSystem.getDevice();
        GpuFormat outputFormat = mainColor.texture().getFormat();

        if (device != activeDevice || outputFormat != activeOutputFormat) {
            cancelPendingReload();
            if (activeDevice != null) {
                deactivate();
            }
            pipelineSessionCache.clear();
            performanceApi.recordSessionCachedPipelines(0);
            activeDevice = device;
            activeOutputFormat = outputFormat;
            PrismPipelineCacheIdentity.persistAndInvalidateMetadata(gameDir, device, outputFormat);
            failedCompile = null;
            closeSampler();
            packFrameUniforms.close();
            watchReloadNotBeforeNanos = 0L;
            settingsReloadNotBeforeNanos = 0L;
            reloadRequested = true;
            reloadReady = true;
        }

        if (reloadRequested && !reloadReady && pendingReload != null) {
            // A newer filesystem/settings revision exists. Do not let the older prepared generation
            // install during the debounce window; keep rendering the last committed generation.
            cancelPendingReload();
        }
        if (reloadRequested && reloadReady) {
            reloadRequested = false;
            watchReloadNotBeforeNanos = 0L;
            settingsReloadNotBeforeNanos = 0L;
            beginReload(device, outputFormat, resources);
        }
        advancePendingReload(device, outputFormat, commandEncoder);
        if (activePack != null) {
            PrismWorldRenderingPipeline.advanceFeatureWarmup(
                    device, PrismClientConfig.get().featureWarmupPerFrame());
        }

        PrismCompiledPack pack = activePack;
        if (pack != null && !pack.graph().orderedPasses().isEmpty()) {
            packResolutionChangedThisFrame = dynamicResolution.consumeChanged();
            observeMainDepthIfNeeded(pack, resources);
            long started = System.nanoTime();
            try {
                packTextureAssets.bind(resources);
                PrismWorldRenderingPipeline.bindSceneAttachments(resources, commandEncoder, mainColor);
                double dynamicScale = dynamicResolution.scale();
                prepareAndBindHistory(pack, device, mainColor, resources, dynamicScale);
                if (packUsesSceneHierarchicalDepth(pack)) {
                    GpuTextureView sceneDepth = resources.requireTexture(PrismHostResources.MAIN_DEPTH);
                    sceneHierarchicalDepth.prepareAndBind(device, commandEncoder, sceneDepth, resources);
                }
                if (packUsesModelMotion(pack)) {
                    modelMotion.prepareAndBind(device, commandEncoder,
                            resources.requireTexture(PrismHostResources.MAIN_DEPTH), resources);
                }
                try {
                    if (packUsesModelViews(pack)) PrismModelViewReplay.prepare(device, commandEncoder);
                    backend.executeGraph(pack.graph(), resources, context, commandEncoder, dynamicScale);
                } finally { PrismModelViewReplay.endFrame(); }
                packHistoryTextures.commitFrame();
                var poolStats = backend.transientPoolStats();
                performanceApi.recordTransientPool(
                        poolStats.createdTextures(),
                        poolStats.closedTextures(),
                        poolStats.createdTextureViews(),
                        poolStats.closedTextureViews(),
                        poolStats.createdBuffers(),
                        poolStats.closedBuffers(),
                        poolStats.reuseHits(),
                        poolStats.cachedResources(),
                        poolStats.activeResources());
            } catch (RuntimeException exception) {
                PrismPackDiagnostic diagnostic = error(
                        "pack_render_error",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                        "runtime");
                PrismChatReporter.error(diagnostic);
                PrismMod.LOGGER.error(
                        "Prism pack '{}' failed during graph execution; disabling the compiled generation until reload: {}",
                        selectorFor(pack.definition()),
                        diagnostic.message(),
                        exception);

                Discovery discovery = discover();
                Selection selection = configuredPackSelector.isEmpty()
                        ? null
                        : resolveSelection(configuredPackSelector, discovery);
                PrismPackInfo failedState = info(
                        pack.definition(),
                        PrismPackStatus.ERROR,
                        pack.generation(),
                        List.of(diagnostic));
                deactivate();
                publishPublicState(discovery, selection, failedState, List.of(), "", reloadGeneration);
                // Do not retry every frame. Files/settings/manual Reload request a fresh generation.
                reloadRequested = false;
            } finally {
                long elapsed = System.nanoTime() - started;
                performanceApi.recordPackFrame(elapsed);
                dynamicResolution.recordFrame(elapsed);
            }
        }
    }

    private void beginReload(GpuDevice device, GpuFormat outputFormat, Blaze3DFrameResources resources) {
        cancelPendingReload();
        long attemptGeneration = ++reloadGeneration;
        performanceApi.recordCompileGeneration();

        Discovery discovery = discover();
        reportDiscoveryErrors(discovery);
        scheduleTransientDiscoveryRetry(discovery);
        Selection selection = selectActive(discovery);
        configuredPackId = selection == null ? "" : selection.packId();
        configuredPackSelector = selection == null ? "" : selection.selector();
        publishSettingsSnapshot(discovery, selection);

        if (selection == null) {
            deactivate();
            commitPendingSelection(DISABLED_SELECTOR);
            publishPublicState(discovery, null, null, List.of(), "", attemptGeneration);
            if (!discovery.valid().isEmpty()) {
                PrismMod.LOGGER.info(
                        "Prism found {} shader pack ids / {} installed versions but none is selected. Use Options > Shaders",
                        discovery.valid().size(),
                        discovery.bySelector().size());
            }
            return;
        }

        PrismPackDefinition selected = selection.definition();
        if (selected == null) {
            PrismPackDiagnostic diagnostic = error(
                    "active_pack_missing",
                    "Selected Prism pack version '" + selection.selector() + "' was not found",
                    "active.txt");
            // Parsing/discovery failures are failed candidates too. Only explicit Off may
            // deactivate a valid generation here; an editor's invalid/temporarily missing
            // manifest must not reset resident capture, history or the installed GPU graph.
            PrismPackInfo retained = activePack == null ? null
                    : info(activePack.definition(), PrismPackStatus.STALE, activePack.generation(), List.of(diagnostic));
            String retainedId = activePack == null ? "" : activePack.definition().id();
            if (activePack == null) deactivate();
            publishPublicState(discovery, selection, retained, List.of(diagnostic), retainedId, attemptGeneration);
            PrismMod.LOGGER.warn("{}{}", diagnostic.message(),
                    activePack == null ? "" : "; keeping previous READY generation active after discovery failure");
            PrismChatReporter.error(diagnostic);
            return;
        }

        List<PrismPackSetting> settings = settingsStore.load(selected);
        long compileFingerprint = mix(packContentFingerprint(selected.root()), settingsFingerprint(settings));
        FailedCompile cachedFailure = failedCompile;
        if (cachedFailure != null
                && cachedFailure.matches(
                        selected.id(),
                        compileFingerprint,
                        PrismPipelineCacheIdentity.fingerprint(device, outputFormat))) {
            boolean retainedSamePack = activePack != null && activePack.definition().id().equals(selected.id());
            String retainedActiveId = activePack == null ? "" : activePack.definition().id();
            PrismPackInfo state = info(
                    selected,
                    retainedSamePack ? PrismPackStatus.STALE : PrismPackStatus.ERROR,
                    retainedSamePack ? activePack.generation() : attemptGeneration,
                    List.of(cachedFailure.diagnostic()));
            publishPublicState(
                    discovery,
                    selection,
                    state,
                    List.of(),
                    retainedActiveId,
                    attemptGeneration);
            performanceApi.recordCachedFailureSkip();
            PrismMod.LOGGER.info(
                    "Prism skipped recompiling unchanged failed pack '{}' (compile fingerprint {}). Edit source/settings to retry.",
                    selection.selector(),
                    Long.toUnsignedString(compileFingerprint, 16));
            PrismChatReporter.error(cachedFailure.diagnostic());
            return;
        }

        try {
            validateSamplerResources(selected, resources);
            PrismCompiledPack previousForReuse = activePack != null
                    && activePack.definition().id().equals(selected.id())
                    ? activePack
                    : null;
            PrismPipelineCompiler.PreparationJob preparation =
                    PrismPipelineCompiler.prepareAsync(selected, settings, preprocessor);
            pendingReload = new PendingReload(
                    attemptGeneration,
                    discovery,
                    selection,
                    selected,
                    settings,
                    compileFingerprint,
                    device,
                    outputFormat,
                    previousForReuse,
                    preparation);
            performanceApi.recordPendingGenerationStarted();
            PrismMod.LOGGER.debug(
                    "Prism generation {} preprocessing scheduled for '{}' on {} workers",
                    attemptGeneration,
                    selection.selector(),
                    PREPROCESS_THREADS);
        } catch (PrismPackLoadException | RuntimeException exception) {
            handleReloadFailure(
                    new PendingReload(
                            attemptGeneration, discovery, selection, selected, settings, compileFingerprint,
                            device, outputFormat, activePack, failedPreparation(exception)),
                    exception);
        }
    }

    private void advancePendingReload(
            GpuDevice device,
            GpuFormat outputFormat,
            CommandEncoder commandEncoder) {
        PendingReload pending = pendingReload;
        if (pending == null) {
            return;
        }
        if (pending.device != device || pending.outputFormat != outputFormat) {
            cancelPendingReload();
            reloadRequested = true;
            return;
        }

        try {
            if (pending.compileSession == null) {
                if (!pending.preparation.isDone()) {
                    return;
                }
                PrismPipelineCompiler.PreparedPack prepared = pending.preparation.join();
                pending.compileSession = PrismPipelineCompiler.beginCompile(
                        prepared,
                        pending.generation,
                        pending.device,
                        pending.outputFormat,
                        pending.previousForReuse,
                        pipelineSessionCache);
            }

            pending.compileSession.step(PrismClientConfig.get().featureWarmupPerFrame());
            performanceApi.recordSessionCachedPipelines(pipelineSessionCache.size());
            if (!pending.compileSession.complete()) {
                return;
            }

            PrismCompiledPack candidate = pending.compileSession.finish();
            GpuTextureView currentMainColor = Minecraft.getInstance().gameRenderer
                    .mainRenderTarget().getColorTextureView();
            PrismPackGpuBudget.requireWithinBudget(
                    candidate, currentMainColor.getWidth(0), currentMainColor.getHeight(0));
            try (Blaze3DPackTextureStore.Prepared preparedTextures =
                    prepareTextureAssets(candidate, device, commandEncoder);
                    PrismSceneAttachmentStore.Prepared preparedSceneAttachments =
                            prepareSceneAttachments(candidate, device, currentMainColor)) {
                int textureCount = preparedTextures.textureCount();
                ensureSamplerIfNeeded(device, candidate);
                install(candidate, preparedSceneAttachments);
                packTextureAssets.install(preparedTextures);
                if (textureCount > 0) {
                    PrismMod.LOGGER.info(
                            "Prism pack texture assets installed: pack={}, textures={}",
                            candidate.definition().id(), textureCount);
                }
            }
            commitPendingSelection(pending.selection.selector());
            successfulCompiles++;
            failedCompile = null;
            performanceApi.recordCompileSuccess(
                    candidate.reusedPipelines(),
                    pending.compileSession.nativeCompiles());

            PrismPackInfo ready = info(
                    pending.selected,
                    PrismPackStatus.READY,
                    pending.generation,
                    candidate.diagnostics());
            publishPublicState(
                    pending.discovery,
                    pending.selection,
                    ready,
                    List.of(),
                    pending.selected.id(),
                    pending.generation);
            PrismProgramSet readyPrograms = pending.selected.programSet();
            PrismMod.LOGGER.info(
                    "Prism pack '{}' {} ready; selector={}, scenePrograms={}, postPrograms={}, computePrograms={}, vanillaReplacements={}, reusedPipelines={}, nativeCompiles={}, settings={}, warnings={}, generation={}",
                    pending.selected.name(),
                    pending.selected.version(),
                    pending.selection.selector(),
                    readyPrograms.scenePrograms().size(),
                    readyPrograms.postPrograms().size(),
                    readyPrograms.computePrograms().size(),
                    pending.selected.vanillaReplacements(),
                    candidate.reusedPipelines(),
                    pending.compileSession.nativeCompiles(),
                    pending.selected.settings().size(),
                    candidate.diagnostics().size(),
                    pending.generation);
            if (pendingReload == pending) {
                performanceApi.recordPendingGenerationFinished();
                pendingReload = null;
            }

            if (successfulCompiles == HOT_RELOAD_CACHE_WARNING) {
                var lifetime = performanceApi.resourceLifetimeSnapshot();
                PrismMod.LOGGER.warn(
                        "Prism has installed {} hot-reload generations this session; liveGenerations={}, liveBindings={}, sessionCachedPipelines={}. Backend-native cache ownership remains inside Blaze3D.",
                        successfulCompiles,
                        lifetime.liveGenerations(),
                        lifetime.livePipelineBindings(),
                        lifetime.sessionCachedPipelines());
            }
        } catch (RuntimeException exception) {
            Throwable unwrapped = PrismPipelineCompiler.unwrapPreparationFailure(exception);
            handleReloadFailure(pending, unwrapped);
        } catch (PrismPackLoadException exception) {
            handleReloadFailure(pending, exception);
        }
    }

    private void handleReloadFailure(PendingReload pending, Throwable failure) {
        if (pending.compileSession != null) pending.compileSession.abort();
        Exception exception = failure instanceof Exception candidate
                ? candidate
                : new RuntimeException(failure);
        PrismPackDiagnostic diagnostic = diagnosticFrom(exception);
        if (exception instanceof PrismPackLoadException && isCacheableCompileFailure(diagnostic.code())) {
            failedCompile = new FailedCompile(
                    pending.selected.id(),
                    pending.compileFingerprint,
                    PrismPipelineCacheIdentity.fingerprint(pending.device, pending.outputFormat),
                    diagnostic);
        } else {
            failedCompile = null;
        }

        // Transactional activation: a failed candidate never tears down the last READY generation,
        // including when the user is switching between different pack ids/versions.
        boolean retainedSamePack = activePack != null
                && activePack.definition().id().equals(pending.selected.id());
        String retainedActiveId = activePack == null ? "" : activePack.definition().id();
        long visibleGeneration = retainedSamePack ? activePack.generation() : pending.generation;
        reconcileSamplerWithActivePack();
        PrismPackInfo state = info(
                pending.selected,
                retainedSamePack ? PrismPackStatus.STALE : PrismPackStatus.ERROR,
                visibleGeneration,
                List.of(diagnostic));
        publishPublicState(
                pending.discovery,
                pending.selection,
                state,
                List.of(),
                retainedActiveId,
                pending.generation);
        PrismMod.LOGGER.error(
                "Prism pack '{}' reload failed{}: {} [{}]",
                pending.selection.selector(),
                activePack != null ? "; keeping previous READY generation active" : "",
                diagnostic.message(),
                diagnostic.source(),
                exception);
        PrismChatReporter.error(diagnostic);
        if (pendingReload == pending) {
            performanceApi.recordPendingGenerationFinished();
            pendingReload = null;
        }
    }

    private void cancelPendingReload() {
        PendingReload pending = pendingReload;
        pendingReload = null;
        if (pending != null) {
            performanceApi.recordPendingGenerationFinished();
            pending.preparation.cancel();
            if (pending.compileSession != null) pending.compileSession.abort();
        }
    }

    private static PrismPipelineCompiler.PreparationJob failedPreparation(Throwable failure) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        return new PrismPipelineCompiler.PreparationJob(
                CompletableFuture.failedFuture(failure),
                List.of(),
                cancelled);
    }

    private static void validateSamplerResources(
            PrismPackDefinition pack,
            Blaze3DFrameResources resources) throws PrismPackLoadException {
        for (PrismPipelineDefinition pipeline : pack.pipelines()) {
            for (PrismSamplerBinding binding : pipeline.samplers()) {
                if (PrismPackResources.SCENE_HIERARCHICAL_DEPTH.equals(binding.resource())
                        || PrismPackResources.MODEL_MOTION.equals(binding.resource())) {
                    try {
                        GpuTextureView depth = resources.requireTexture(PrismHostResources.MAIN_DEPTH);
                        if ((depth.texture().usage()
                                & com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
                            throw new PrismPackLoadException(
                                    "derived_scene_depth_usage",
                                    binding.resource() + " requires sampleable minecraft:main_depth",
                                    "prism.json");
                        }
                    } catch (PrismPackLoadException exception) {
                        throw exception;
                    } catch (RuntimeException exception) {
                        throw new PrismPackLoadException(
                                "derived_scene_depth_missing",
                                binding.resource() + " requires the Minecraft main depth texture",
                                "prism.json",
                                exception);
                    }
                    continue;
                }
                if (pack.resource(binding.resource()) != null
                        || pack.textureAsset(binding.resource()) != null) continue;
                final GpuTextureView view;
                try {
                    view = resources.requireTexture(PrismPackResources.toInternal(binding.resource()));
                } catch (RuntimeException exception) {
                    throw new PrismPackLoadException(
                            "sampler_resource_missing",
                            "Sampler '" + binding.name() + "' requires unavailable resource '"
                                    + binding.resource() + "'",
                            "prism.json",
                            exception);
                }
                if ((view.texture().usage() & com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
                    throw new PrismPackLoadException(
                            "sampler_resource_usage",
                            "Resource '" + binding.resource() + "' is not texture-bindable on this Minecraft backend",
                            "prism.json");
                }
            }
        }
    }

    private final PrismModelMotionRenderer modelMotion = new PrismModelMotionRenderer();
    private static boolean packUsesModelViews(PrismCompiledPack pack) {
        return pack.definition().pipelines().stream().anyMatch(p -> p.isSceneView() && p.sceneView().capturedModels());
    }

    private static boolean packUsesModelMotion(PrismCompiledPack pack) {
        return pack.definition().pipelines().stream().flatMap(p -> p.samplers().stream())
                .anyMatch(b -> PrismPackResources.MODEL_MOTION.equals(b.resource()));
    }

    private void install(
            PrismCompiledPack candidate,
            PrismSceneAttachmentStore.Prepared preparedSceneAttachments) {
        List<String> newlyRegistered = new ArrayList<>();
        try {
            for (PrismCompiledFullscreenPipeline pipeline : candidate.pipelines()) {
                backend.registerPassExecutor(
                        pipeline.passName(),
                        context -> {
                            long started = System.nanoTime();
                            try {
                                GpuBuffer frameUniformBuffer = pipeline.usesFrameUniforms()
                                        ? packFrameUniforms.requireBuffer(
                                                RenderSystem.getDevice(),
                                                context.requireCommandEncoder(),
                                                !packUsesTemporalHistory(candidate)
                                                        || packHistoryTextures.historyValid(),
                                                dynamicResolution.scale(),
                                                packResolutionChangedThisFrame)
                                        : null;
                                pipeline.execute(
                                        context,
                                        sharedSamplers,
                                        frameUniformBuffer,
                                        gpuProfiler);
                            } finally {
                                performanceApi.recordPass(pipeline.pipelineId(), System.nanoTime() - started);
                            }
                        });
                newlyRegistered.add(pipeline.passName());
            }
            for (PrismCompiledComputePipeline pipeline : candidate.computePipelines()) {
                backend.registerPassExecutor(
                        pipeline.passName(),
                        context -> {
                            long started = System.nanoTime();
                            try {
                                GpuBuffer frameUniformBuffer = pipeline.usesFrameUniforms()
                                        ? packFrameUniforms.requireBuffer(
                                                RenderSystem.getDevice(), context.requireCommandEncoder(),
                                                !packUsesTemporalHistory(candidate)
                                                        || packHistoryTextures.historyValid(),
                                                dynamicResolution.scale(),
                                                packResolutionChangedThisFrame)
                                        : null;
                                pipeline.execute(context, sharedSamplers, frameUniformBuffer);
                            } finally {
                                performanceApi.recordPass(pipeline.pipelineId(), System.nanoTime() - started);
                            }
                        });
                newlyRegistered.add(pipeline.passName());
            }

            // Commit scene replacement only after every candidate pipeline and fullscreen executor is
            // ready. The registry swap is one volatile publication, so chunk rendering never sees a
            // half-installed generation.
            PrismWorldRenderingPipeline.install(
                    candidate.definition().id(),
                    candidate.generation(),
                    candidate.scenePipelines(),
                    candidate.featurePrograms(),
                    candidate.definition().vanillaReplacements(),
                    preparedSceneAttachments);
        } catch (RuntimeException exception) {
            for (String pass : newlyRegistered) {
                backend.unregisterPassExecutor(pass);
            }
            candidate.computePipelines().forEach(PrismCompiledComputePipeline::close);
            throw exception;
        }

        PrismCompiledPack previous = activePack;
        // Shader/source/settings reloads intentionally invalidate temporal accumulation. Keeping
        // history across a generation boundary would let a changed algorithm reinterpret stale data.
        packHistoryTextures.close();
        activePack = candidate;
        PrismModelMotionCapture.reset();
        PrismModelMotionCapture.setEnabled(packUsesModelMotion(candidate));
        modelMotion.close();
        PrismModelViewCapture.setEnabled(packUsesModelViews(candidate));
        PrismResidentModelViews.configure(candidate.definition().pipelines());
        PrismModelViewReplay.close();
        if (!packUsesSceneHierarchicalDepth(candidate)) {
            sceneHierarchicalDepth.close();
        }
        dynamicResolution.configure(PrismClientConfig.get().dynamicResolution()
                ? candidate.definition().dynamicResolution()
                : PrismDynamicResolutionDefinition.DISABLED);
        PrismProjectionJitter.setEnabled(candidate.definition().sceneJitter());
        List<String> pipelineIds = candidate.pipelineIds();
        performanceApi.recordGenerationInstalled(candidate.totalPipelineCount());
        performanceApi.configure(
                candidate.definition().id(),
                pipelineIds,
                candidate.graph().transientPlan().slotByResource().size(),
                candidate.graph().transientPlan().slotCount());
        performanceApi.configureGraph(candidate.definition().id(), candidate.graph());
        if (activeDevice != null && PrismClientConfig.get().gpuProfiling()) {
            // GPU timestamp instrumentation currently wraps Prism-owned fullscreen passes. Scene
            // terrain timing remains part of Minecraft's terrain draw until a stage-aware profiler is added.
            gpuProfiler.configure(
                    activeDevice,
                    candidate.pipelines().stream().map(PrismCompiledFullscreenPipeline::pipelineId).toList(),
                    performanceApi);
        } else {
            gpuProfiler.close();
        }
        if (previous != null) {
            unregister(previous);
        }
        reconcileSamplerWithActivePack();
        if (!packNeedsFrameUniforms(activePack)) {
            packFrameUniforms.close();
        }
        PrismMod.LOGGER.info(
                "Prism committed generation {} for '{}': terrainPipelines={}, featurePrograms={}, fullscreenPipelines={}, computePipelines={}, reused={}",
                candidate.generation(), candidate.definition().id(), candidate.scenePipelines().size(),
                candidate.featurePrograms().size(), candidate.pipelines().size(),
                candidate.computePipelines().size(), candidate.reusedPipelines());
    }

    private void deactivate() {
        lastObservedDepthView = null;
        // Publish vanilla fallback before unregistering the rest of the generation. Any concurrent
        // layer.pipeline() lookup immediately gets Minecraft's original pipeline.
        PrismWorldRenderingPipeline.clear();
        if (activePack != null) {
            unregister(activePack);
            activePack = null;
        }
        closeSampler();
        packFrameUniforms.close();
        packHistoryTextures.close();
        packTextureAssets.close();
        sceneHierarchicalDepth.close();
        PrismModelMotionCapture.setEnabled(false);
        modelMotion.close();
        PrismModelViewCapture.setEnabled(false);
        PrismResidentModelViews.configure(List.of());
        PrismModelViewReplay.close();
        dynamicResolution.reset();
        PrismProjectionJitter.setEnabled(false);
        performanceApi.deactivate();
        gpuProfiler.deactivate();
    }

    private void unregister(PrismCompiledPack pack) {
        for (PrismCompiledFullscreenPipeline pipeline : pack.pipelines()) {
            backend.unregisterPassExecutor(pipeline.passName());
        }
        for (PrismCompiledComputePipeline pipeline : pack.computePipelines()) {
            backend.unregisterPassExecutor(pipeline.passName());
            pipeline.close();
        }
        performanceApi.recordGenerationRetired(pack.totalPipelineCount());
    }

    private void ensureSamplerIfNeeded(GpuDevice device, PrismCompiledPack pack) {
        for (PrismSamplerBinding binding : pack.definition().pipelines().stream()
                .flatMap(pipeline -> pipeline.samplers().stream()).toList()) {
            if (sharedSamplers.containsKey(binding.samplerKey())) continue;
            AddressMode address = binding.repeat() ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
            FilterMode filter = binding.nearest() ? FilterMode.NEAREST : FilterMode.LINEAR;
            sharedSamplers.put(binding.samplerKey(), device.createSampler(
                    address, address, filter, filter, 1, OptionalDouble.empty()));
            performanceApi.recordSamplerCreated();
        }
    }

    private void reconcileSamplerWithActivePack() {
        if (activePack == null || !packNeedsSampler(activePack)) {
            closeSampler();
            return;
        }
        java.util.Set<String> required = new java.util.HashSet<>();
        activePack.definition().pipelines().stream()
                .flatMap(pipeline -> pipeline.samplers().stream())
                .map(PrismSamplerBinding::samplerKey)
                .forEach(required::add);
        var iterator = sharedSamplers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (required.contains(entry.getKey())) continue;
            closeSamplerResource(entry.getValue(), entry.getKey());
            iterator.remove();
            performanceApi.recordSamplerClosed();
        }
    }

    private static boolean packNeedsSampler(PrismCompiledPack pack) {
        return pack != null && (pack.pipelines().stream().anyMatch(PrismCompiledFullscreenPipeline::hasSamplers)
                || pack.computePipelines().stream().anyMatch(PrismCompiledComputePipeline::hasSamplers));
    }

    private static boolean packNeedsFrameUniforms(PrismCompiledPack pack) {
        return pack != null && (pack.pipelines().stream()
                .anyMatch(PrismCompiledFullscreenPipeline::usesFrameUniforms)
                || pack.computePipelines().stream().anyMatch(PrismCompiledComputePipeline::usesFrameUniforms));
    }

    private static boolean packUsesTemporalHistory(PrismCompiledPack pack) {
        return pack != null && pack.definition().resources().stream()
                .anyMatch(PrismPackTextureDefinition::history);
    }

    private static boolean packUsesSceneHierarchicalDepth(PrismCompiledPack pack) {
        return pack != null && pack.definition().pipelines().stream()
                .flatMap(pipeline -> pipeline.samplers().stream())
                .anyMatch(binding -> PrismPackResources.SCENE_HIERARCHICAL_DEPTH.equals(binding.resource()));
    }

    private Blaze3DPackTextureStore.Prepared prepareTextureAssets(
            PrismCompiledPack pack,
            GpuDevice device,
            CommandEncoder commandEncoder) {
        List<Blaze3DPackTextureStore.Definition> definitions = pack.definition().textureAssets().stream()
                .map(texture -> new Blaze3DPackTextureStore.Definition(
                        PrismPackResources.toInternal(texture.id()),
                        texture.sources().stream()
                                .map(source -> pack.definition().root().resolve(source))
                                .toList(),
                        texture.dimension(),
                        texture.width(),
                        texture.height(),
                        texture.colorSpace(),
                        texture.mipLevels()))
                .toList();
        Blaze3DPackTextureStore.Prepared prepared = packTextureAssets.prepare(
                device, commandEncoder, definitions);
        return prepared;
    }

    private static PrismSceneAttachmentStore.Prepared prepareSceneAttachments(
            PrismCompiledPack pack,
            GpuDevice device,
            GpuTextureView mainColor) {
        List<PrismSceneAttachmentStore.Definition> definitions = pack.definition().resources().stream()
                .filter(PrismPackTextureDefinition::scene)
                .map(resource -> new PrismSceneAttachmentStore.Definition(
                        resource.id(), PrismPipelineCompiler.gpuFormat(resource.descriptor().format())))
                .toList();
        return PrismSceneAttachmentStore.prepare(
                device, mainColor.getWidth(0), mainColor.getHeight(0), definitions);
    }

    private void prepareAndBindHistory(
            PrismCompiledPack pack,
            GpuDevice device,
            GpuTextureView mainColor,
            Blaze3DFrameResources resources,
            double dynamicScale) {
        List<Blaze3DHistoryTextureStore.Definition> definitions = pack.definition().resources().stream()
                .filter(PrismPackTextureDefinition::history)
                .map(resource -> new Blaze3DHistoryTextureStore.Definition(
                        PrismPackResources.toInternal(resource.id()),
                        PrismPackResources.previousHistory(resource.id()),
                        resource.descriptor()))
                .toList();
        if (definitions.isEmpty()) {
            return;
        }
        boolean recreated = packHistoryTextures.prepare(
                device,
                mainColor.getWidth(0),
                mainColor.getHeight(0),
                dynamicScale,
                definitions);
        packHistoryTextures.bind(resources);
        if (recreated) {
            PrismMod.LOGGER.info(
                    "Prism temporal history allocated: pack={}, pairs={}, reference={}x{}, allocationGeneration={}",
                    pack.definition().id(),
                    packHistoryTextures.pairCount(),
                    mainColor.getWidth(0),
                    mainColor.getHeight(0),
                    packHistoryTextures.allocationGeneration());
        }
    }

    private Discovery discover() {
        List<Discovered> discovered = new ArrayList<>();
        scanPackRoot(packsRoot, false, discovered);
        if (Files.isDirectory(legacyPacksRoot)) {
            scanPackRoot(legacyPacksRoot, true, discovered);
        }

        // Shader Library 2: different versions of the same pack id are valid side-by-side.
        // Only the ambiguous case of the exact same id+version from multiple sources remains an error.
        Map<String, List<Discovered>> exactVersions = new LinkedHashMap<>();
        for (Discovered item : discovered) {
            if (item.definition() != null && item.diagnostic() == null) {
                exactVersions.computeIfAbsent(selectorFor(item.definition()), ignored -> new ArrayList<>()).add(item);
            }
        }

        List<Discovered> normalized = new ArrayList<>(discovered.size());
        Map<String, PrismPackDefinition> bySelector = new LinkedHashMap<>();
        for (Discovered item : discovered) {
            PrismPackDefinition definition = item.definition();
            if (definition == null) {
                normalized.add(item);
                continue;
            }

            if (item.diagnostic() != null) {
                normalized.add(item);
                continue;
            }

            String selector = selectorFor(definition);
            List<Discovered> sameVersion = exactVersions.getOrDefault(selector, List.of());
            if (sameVersion.size() > 1) {
                normalized.add(new Discovered(
                        item.path(),
                        definition,
                        error(
                                "duplicate_pack_version",
                                "Multiple shader-pack sources declare the exact same id/version '"
                                        + selector + "'. Keep only one copy of that version.",
                                item.path().getFileName().toString()),
                        item.archive(),
                        item.legacy()));
                continue;
            }

            normalized.add(item);
            bySelector.put(selector, definition);
        }

        Map<String, PrismPackDefinition> preferred = new LinkedHashMap<>();
        for (PrismPackDefinition definition : bySelector.values()) {
            PrismPackDefinition current = preferred.get(definition.id());
            if (current == null || comparePackVersions(definition.version(), current.version()) > 0) {
                preferred.put(definition.id(), definition);
            }
        }

        return new Discovery(
                Map.copyOf(preferred),
                Map.copyOf(bySelector),
                List.copyOf(normalized));
    }

    private void scanPackRoot(Path root, boolean legacy, List<Discovered> output) {
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.list(root)) {
            for (Path path : stream.sorted(Comparator.comparing(
                    p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT))).toList()) {
                boolean archive = Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
                boolean directory = Files.isDirectory(path);
                if (!archive && !directory) continue;

                try {
                    Path materialized = archive ? zipCache.materialize(path) : path;
                    Path packRoot = PrismPackRootLocator.locate(materialized);
                    PrismPackDefinition definition;

                    if (Files.isRegularFile(packRoot.resolve("prism.json"))) {
                        definition = PrismPackParser.parse(packRoot);
                    } else if (PrismGlslFrontend.looksLike(packRoot)) {
                        definition = PrismGlslFrontend.load(packRoot, path.getFileName().toString());
                    } else if (PrismNativePackLoader.looksLikeNativePack(packRoot)) {
                        definition = PrismNativePackLoader.load(packRoot, path.getFileName().toString());
                    } else if (PrismLegacyPackInspector.looksLikeLegacyPack(packRoot)) {
                        output.add(new Discovered(
                                path,
                                null,
                                PrismLegacyPackInspector.diagnostic(packRoot, path.getFileName().toString()),
                                archive,
                                legacy));
                        continue;
                    } else {
                        // Ordinary folders inside shaderpacks are ignored. ZIPs are surfaced because a
                        // user deliberately installed them and needs an actionable diagnostic.
                        if (archive) {
                            output.add(new Discovered(
                                    path,
                                    null,
                                    error(
                                            "pack_format_unrecognized",
                                            "ZIP is neither a Prism Native pack nor a recognized classic shader pack",
                                            path.getFileName().toString()),
                                    true,
                                    legacy));
                        }
                        continue;
                    }

                    output.add(new Discovered(
                            path,
                            definition,
                            capabilityCompatibilityDiagnostic(definition),
                            archive,
                            legacy));
                } catch (PrismPackLoadException exception) {
                    output.add(new Discovered(path, null, diagnosticFrom(exception), archive, legacy));
                }
            }
        } catch (IOException exception) {
            PrismMod.LOGGER.error("Could not scan Prism shader-pack directory {}", root, exception);
            PrismChatReporter.error("pack_scan_error", "Could not scan shader-pack directory: " + root, root.toString());
        }
    }

    private PrismPackDiagnostic capabilityCompatibilityDiagnostic(PrismPackDefinition definition) {
        if (definition.requiredCapabilities().isEmpty()) return null;
        java.util.Set<dev.dreamveil.prism.api.PrismCapability> available = backend.info().capabilities();
        List<String> missing = definition.requiredCapabilities().stream()
                .filter(capability -> !available.contains(capability))
                .map(capability -> capability.name().toLowerCase(java.util.Locale.ROOT))
                .toList();
        if (missing.isEmpty()) return null;
        String source = Files.isRegularFile(definition.root().resolve("prism.json"))
                ? "prism.json:requires"
                : "prism.properties:prism_api";
        return error(
                "pack_incompatible",
                "This shader pack requires unsupported Prism capabilities: " + String.join(", ", missing),
                source);
    }

    private static String selectorFor(PrismPackDefinition definition) {
        return new PrismPackSelector(definition.id(), definition.version()).persisted();
    }

    private static String selectorPackId(String selector) {
        if (selector == null || selector.isBlank()) return "";
        return PrismPackSelector.parse(selector).id();
    }

    public static int comparePackVersions(String left, String right) {
        return PrismPackVersion.compare(left, right);
    }

    private Selection selectActive(Discovery discovery) {
        String property = System.getProperty("dreamveil.prism.pack", "").trim();
        if (!property.isEmpty()) {
            return resolveSelection(property, discovery);
        }

        String pending = pendingSelectionOverride;
        if (pending != null) {
            return resolveSelection(pending, discovery);
        }

        for (Path activeFile : List.of(packsRoot.resolve("active.txt"), legacyPacksRoot.resolve("active.txt"))) {
            if (!Files.isRegularFile(activeFile)) continue;
            try {
                for (String line : Files.readAllLines(activeFile, StandardCharsets.UTF_8)) {
                    String value = line.trim();
                    if (!value.isEmpty() && !value.startsWith("#")) {
                        return resolveSelection(value, discovery);
                    }
                }
            } catch (IOException exception) {
                PrismMod.LOGGER.warn("Could not read Prism active pack selector {}", activeFile, exception);
            }
        }

        return null;
    }

    private Selection resolveSelection(String value, Discovery discovery) {
        if (value == null || value.isBlank() || isDisabledSelector(value)) {
            return null;
        }
        String normalized = value.trim();

        // Prism 0.8 selector: pack.id@version.
        PrismPackDefinition exact = discovery.bySelector().get(normalized);
        if (exact != null) {
            return new Selection(normalized, exact.id(), exact);
        }

        // Backward compatible selector: pack.id. Resolve to the highest installed version.
        PrismPackDefinition preferred = discovery.valid().get(normalized);
        if (preferred != null) {
            return new Selection(selectorFor(preferred), preferred.id(), preferred);
        }

        return new Selection(normalized, selectorPackId(normalized), null);
    }

    private static boolean isDisabledSelector(String value) {
        return DISABLED_SELECTOR.equalsIgnoreCase(value) || "off".equalsIgnoreCase(value);
    }

    private static PrismPackMetadata metadataOf(Discovered item) {
        PrismPackDefinition definition = item.definition();
        return definition == null
                ? PrismPackMetadata.EMPTY
                : new PrismPackMetadata(
                        definition.author(),
                        definition.description(),
                        item.path().getFileName().toString(),
                        item.archive());
    }

    private static Map<String, PrismPackMetadata> metadataFor(Discovery discovery, Selection selected) {
        Map<String, PrismPackMetadata> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, PrismPackDefinition> entry : discovery.valid().entrySet()) {
            PrismPackDefinition definition = entry.getValue();
            if (selected != null && selected.definition() != null && entry.getKey().equals(selected.packId())) {
                definition = selected.definition();
            }
            Discovered source = findDiscovered(discovery, definition);
            if (source != null) metadata.put(entry.getKey(), metadataOf(source));
        }
        return Map.copyOf(metadata);
    }

    private static Map<String, List<PrismPackVariant>> variantsFor(Discovery discovery, Selection selected) {
        Map<String, List<PrismPackVariant>> variants = new LinkedHashMap<>();
        for (Discovered item : discovery.discovered()) {
            PrismPackDefinition definition = item.definition();
            if (definition == null) continue;

            PrismPackDefinition preferred = discovery.valid().get(definition.id());
            boolean isPreferred = item.diagnostic() == null && preferred != null
                    && selectorFor(preferred).equals(selectorFor(definition));
            boolean isSelected = item.diagnostic() == null && selected != null
                    && selected.definition() != null
                    && selectorFor(selected.definition()).equals(selectorFor(definition));

            variants.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(new PrismPackVariant(
                    definition.id(),
                    definition.name(),
                    definition.version(),
                    metadataOf(item),
                    isPreferred,
                    isSelected,
                    item.diagnostic() == null ? List.of() : List.of(item.diagnostic())));
        }

        variants.replaceAll((id, values) -> values.stream()
                .sorted((a, b) -> {
                    int version = comparePackVersions(b.version(), a.version());
                    if (version != 0) return version;
                    return a.metadata().sourceName().compareToIgnoreCase(b.metadata().sourceName());
                })
                .toList());
        return Map.copyOf(variants);
    }

    private static Discovered findDiscovered(Discovery discovery, PrismPackDefinition definition) {
        if (definition == null) return null;
        String selector = selectorFor(definition);
        for (Discovered item : discovery.discovered()) {
            if (item.definition() != null && item.diagnostic() == null
                    && selectorFor(item.definition()).equals(selector)) {
                return item;
            }
        }
        return null;
    }

    /** Compatibility UI control: selects the preferred/highest installed version for a pack id. */
    public boolean selectPack(String packId) {
        String normalized = packId == null ? "" : packId.trim();
        if (normalized.isEmpty()) {
            return selectPackVersion("");
        }

        Discovery discovery = discover();
        PrismPackDefinition preferred = discovery.valid().get(normalized);
        if (preferred == null) {
            PrismMod.LOGGER.warn("Prism UI refused unknown or invalid shader pack id '{}'", normalized);
            PrismChatReporter.error("pack_selection_invalid", "Unknown or invalid shader pack id '" + normalized + "'", "Shaders");
            return false;
        }
        return selectPackVersion(selectorFor(preferred));
    }

    /** Shader Library 2 control: selects one concrete id@version variant. */
    public boolean selectPackVersion(String selector) {
        String normalized = selector == null ? "" : selector.trim();
        Discovery discovery = discover();
        Selection selection = normalized.isEmpty() ? null : resolveSelection(normalized, discovery);
        if (selection != null && selection.definition() == null) {
            PrismMod.LOGGER.warn("Prism UI refused unknown or ambiguous shader pack version '{}'", normalized);
            PrismChatReporter.error("pack_selection_invalid", "Unknown or ambiguous shader pack version '" + normalized + "'", "Shaders");
            return false;
        }

        String nextSelector = selection == null ? "" : selection.selector();
        if (configuredPackSelector.equals(nextSelector)
                && ((selection == null && configuredPackId.isEmpty())
                        || (selection != null && configuredPackId.equals(selection.packId())))) {
            PrismMod.LOGGER.debug(
                    "Prism shader-pack selector '{}' is already configured; selection is a no-op",
                    selection == null ? "Off / Vanilla" : selection.selector());
            return true;
        }

        String persisted = selection == null ? DISABLED_SELECTOR : selection.selector();
        // Candidate selection is intentionally not persisted yet. A new pack/version becomes
        // durable only after a complete candidate generation reaches READY. This prevents a
        // broken selection from trapping the next launch in the same failure.
        pendingSelectionOverride = persisted;

        configuredPackId = selection == null ? "" : selection.packId();
        configuredPackSelector = selection == null ? "" : selection.selector();
        watchReloadNotBeforeNanos = 0L;
        settingsReloadNotBeforeNanos = 0L;
        failedCompile = null;
        reloadRequested = true;

        publishSettingsSnapshot(discovery, selection);
        String currentlyActiveId = activePack == null ? "" : activePack.definition().id();
        publishPublicState(discovery, selection, null, List.of(), currentlyActiveId, reloadGeneration);
        PrismMod.LOGGER.info(
                "Prism shader-pack selection changed to '{}'",
                selection == null ? "Off / Vanilla" : selection.selector());
        return true;
    }

    private boolean persistSelection(String selector) {
        Path activeFile = packsRoot.resolve("active.txt");
        Path temporary = packsRoot.resolve("active.txt.tmp");
        try {
            Files.createDirectories(packsRoot);
            Files.writeString(temporary, selector + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        activeFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, activeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            PrismMod.LOGGER.error("Could not persist Prism shader-pack selection to {}", activeFile, exception);
            PrismChatReporter.error("pack_selection_write", "Could not save selected shader pack", activeFile.toString());
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            return false;
        }
    }

    public String configuredPackId() {
        return configuredPackId;
    }

    public String configuredPackSelector() {
        return configuredPackSelector;
    }

    public String activePackSelector() {
        PrismCompiledPack pack = activePack;
        return pack == null ? "" : selectorFor(pack.definition());
    }

    public String preferredSelector(String packId) {
        if (packId == null || packId.isBlank()) return "";
        PrismPackDefinition preferred = discover().valid().get(packId);
        return preferred == null ? "" : selectorFor(preferred);
    }

    public void requestReload() {
        failedCompile = null;
        watchReloadNotBeforeNanos = 0L;
        settingsReloadNotBeforeNanos = 0L;
        reloadRequested = true;
    }

    /** Publishes vanilla fallbacks before Minecraft invalidates Blaze3D's native shader cache. */
    public void beforeMinecraftShaderResourcesReload() {
        if (closed) return;
        RenderSystem.assertOnRenderThread();
        shaderResourceReloadInProgress = true;
        cancelPendingReload();
        PrismShadowRenderer.beginShaderResourceReload();
        boolean hadActivePack = activePack != null;
        deactivate();
        pipelineSessionCache.clear();
        performanceApi.recordSessionCachedPipelines(0);
        reloadRequested = false;
        failedCompile = null;
        PrismMod.LOGGER.info(
                "Minecraft shader-resource reload entered; Prism published vanilla fallbacks before native cache invalidation (hadActivePack={})",
                hadActivePack);
    }

    /** Starts a fresh generation only after all Vanilla client-resource reloaders have completed. */
    public void afterMinecraftShaderResourcesReload() {
        if (closed) return;
        RenderSystem.assertOnRenderThread();
        if (activePack != null) {
            // Defensive guard for unusual third-party reload ordering.
            deactivate();
            pipelineSessionCache.clear();
            performanceApi.recordSessionCachedPipelines(0);
        }
        PrismShadowRenderer.endShaderResourceReload();
        shaderResourceReloadInProgress = false;
        requestReload();
        PrismMod.LOGGER.info(
                "Minecraft shader-resource reload completed; Prism native reuse state is invalid and the selected pack will compile as a fresh generation");
    }

    /** Rescans manifests for the UI without compiling pipelines or touching GPU state. */
    public void refreshDiscoveryForUi() {
        publishDiscoverySnapshot();
    }

    /** Monotonic filesystem-library change counter used by the open Shaders screen. */
    public long libraryRevision() {
        return libraryRevision;
    }

    /** Performs a live UI rescan only after bursty filesystem copy events have settled. */
    public boolean refreshDiscoveryForUiIfReady() {
        long now = System.nanoTime();
        long notBefore = watchReloadNotBeforeNanos;
        if (notBefore != 0L && now < notBefore) return false;
        if (!libraryStableForReload(now)) return false;
        publishDiscoverySnapshot();
        return true;
    }

    public Path packsRoot() {
        return packsRoot;
    }

    public boolean openPacksFolder() {
        return openFolder(packsRoot, "shaderpacks");
    }

    public Path gameDir() {
        return gameDir;
    }

    /** Opens the selected shader pack folder, or the containing folder for a ZIP pack. */
    public boolean openSelectedPackLocation() {
        Discovery discovery = discover();
        Selection selection = configuredPackSelector.isEmpty()
                ? null
                : resolveSelection(configuredPackSelector, discovery);
        if (selection == null || selection.definition() == null) {
            return false;
        }
        Discovered source = findDiscovered(discovery, selection.definition());
        if (source == null) {
            return false;
        }
        Path target = source.archive() ? source.path().getParent() : source.path();
        return target != null && openFolder(target, "selected shader pack");
    }

    public Path selectedPackSourcePath() {
        Discovery discovery = discover();
        Selection selection = configuredPackSelector.isEmpty()
                ? null
                : resolveSelection(configuredPackSelector, discovery);
        if (selection == null || selection.definition() == null) return null;
        Discovered source = findDiscovered(discovery, selection.definition());
        return source == null ? null : source.path();
    }

    private static boolean openFolder(Path folder, String label) {
        Path normalized = folder.toAbsolutePath().normalize();
        Exception desktopFailure = null;
        try {
            Files.createDirectories(normalized);
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                    try {
                        desktop.open(normalized.toFile());
                        return true;
                    } catch (Exception exception) {
                        desktopFailure = exception;
                        PrismMod.LOGGER.debug("Java Desktop.open failed for Prism {} folder {}; trying OS fallback", label, normalized, exception);
                    }
                }
            }

            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            ProcessBuilder builder;
            if (os.contains("win")) {
                builder = new ProcessBuilder("explorer.exe", normalized.toString());
            } else if (os.contains("mac")) {
                builder = new ProcessBuilder("open", normalized.toString());
            } else {
                builder = new ProcessBuilder("xdg-open", normalized.toString());
            }
            builder.redirectErrorStream(true).start();
            PrismMod.LOGGER.info("Opened Prism {} folder using OS fallback: {}", label, normalized);
            return true;
        } catch (Exception exception) {
            if (desktopFailure != null) exception.addSuppressed(desktopFailure);
            PrismMod.LOGGER.warn("Could not open Prism {} folder {}", label, normalized, exception);
            PrismChatReporter.error(
                    "open_folder_failed",
                    "Could not open Prism " + label + " folder: " + exception.getMessage(),
                    normalized.toString());
            return false;
        }
    }

    private void commitPendingSelection(String selector) {
        String pending = pendingSelectionOverride;
        if (pending == null) return;
        if (!pending.equals(selector)) return;
        if (persistSelection(selector)) {
            pendingSelectionOverride = null;
            PrismMod.LOGGER.info("Prism committed last-known-good shader selection '{}'", selector);
        }
    }

    private boolean libraryStableForReload(long now) {
        if (!libraryDirty) return true;
        long fingerprint = packLibraryMetadataFingerprint();
        if (fingerprint != lastLibraryMetadataFingerprint) {
            lastLibraryMetadataFingerprint = fingerprint;
            libraryStableSinceNanos = now;
            return false;
        }
        if (libraryStableSinceNanos == 0L) {
            libraryStableSinceNanos = now;
            return false;
        }
        if (now - libraryStableSinceNanos < LIBRARY_STABLE_NANOS) return false;
        libraryDirty = false;
        libraryStableSinceNanos = 0L;
        return true;
    }

    private long packLibraryMetadataFingerprint() {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, packRootMetadataFingerprint(packsRoot));
        if (Files.isDirectory(legacyPacksRoot)) hash = mix(hash, packRootMetadataFingerprint(legacyPacksRoot));
        return hash;
    }

    private static long packRootMetadataFingerprint(Path root) {
        if (!Files.isDirectory(root)) return 0L;
        long hash = 0xcbf29ce484222325L;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith(".prism-cache/") || relative.equals("active.txt") || relative.equals("active.txt.tmp")) continue;
                hash = mix(hash, relative.hashCode());
                try {
                    hash = mix(hash, Files.size(path));
                    hash = mix(hash, Files.getLastModifiedTime(path).toMillis());
                } catch (IOException exception) {
                    hash = mix(hash, -1L);
                }
            }
        } catch (IOException exception) {
            hash = mix(hash, -2L);
        }
        return hash;
    }

    private void scheduleTransientDiscoveryRetry(Discovery discovery) {
        boolean transientError = discovery.discovered().stream()
                .map(Discovered::diagnostic)
                .filter(java.util.Objects::nonNull)
                .map(PrismPackDiagnostic::code)
                .anyMatch(code -> code.equals("archive_read") || code.equals("manifest_read"));
        if (!transientError) {
            transientDiscoveryRetryCount = 0;
            return;
        }
        if (transientDiscoveryRetryCount >= MAX_TRANSIENT_DISCOVERY_RETRIES) return;

        long delayMillis = Math.min(4_000L, 250L << transientDiscoveryRetryCount);
        transientDiscoveryRetryCount++;
        watchReloadNotBeforeNanos = Math.max(
                watchReloadNotBeforeNanos,
                System.nanoTime() + delayMillis * 1_000_000L);
        reloadRequested = true;
        PrismMod.LOGGER.debug(
                "Prism scheduled transient shader-library I/O retry {}/{} in {} ms",
                transientDiscoveryRetryCount,
                MAX_TRANSIENT_DISCOVERY_RETRIES,
                delayMillis);
    }

    private void reportDiscoveryErrors(Discovery discovery) {
        for (Discovered discovered : discovery.discovered()) {
            if (discovered.diagnostic() != null
                    && discovered.diagnostic().severity() == PrismDiagnosticSeverity.ERROR
                    && !"pack_incompatible".equals(discovered.diagnostic().code())) {
                PrismChatReporter.error(discovered.diagnostic());
            }
        }
    }

    private void observeMainDepthIfNeeded(PrismCompiledPack pack, Blaze3DFrameResources resources) {
        boolean samplesDepth = pack.definition().pipelines().stream()
                .flatMap(pipeline -> pipeline.samplers().stream())
                .anyMatch(binding -> "minecraft:main_depth".equals(binding.resource()));
        if (!samplesDepth) {
            return;
        }

        final GpuTextureView depth;
        try {
            depth = resources.requireTexture(PrismHostResources.MAIN_DEPTH);
        } catch (RuntimeException exception) {
            PrismChatReporter.error(
                    "main_depth_missing",
                    "Selected shader pack requires minecraft:main_depth, but the main target has no depth texture",
                    "minecraft:main_depth");
            return;
        }
        if (depth == lastObservedDepthView) {
            return;
        }
        lastObservedDepthView = depth;
        var texture = depth.texture();
        PrismMod.LOGGER.info(
                "Prism main depth diagnostic: format={}, size={}x{}, layers={}, mipLevels={}, usage=0x{}, textureBinding={}, reversedZ=true",
                texture.getFormat(),
                texture.getWidth(0),
                texture.getHeight(0),
                texture.getDepthOrLayers(),
                texture.getMipLevels(),
                Integer.toHexString(texture.usage()),
                (texture.usage() & com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING) != 0);
    }

    private PrismPackDefinition definitionForPackId(Discovery discovery, String packId) {
        if (packId == null) return null;
        if (packId.equals(configuredPackId) && !configuredPackSelector.isEmpty()) {
            PrismPackDefinition configured = discovery.bySelector().get(configuredPackSelector);
            if (configured != null) return configured;
        }
        return discovery.valid().get(packId);
    }

    private boolean setSetting(String packId, String settingId, String rawValue) {
        if (packId == null || settingId == null || rawValue == null) {
            return false;
        }
        Discovery discovery = discover();
        PrismPackDefinition pack = definitionForPackId(discovery, packId);
        if (pack == null) {
            return false;
        }
        PrismPackSettingDefinition definition = pack.settings().stream()
                .filter(setting -> setting.id().equals(settingId))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            return false;
        }

        final String normalized;
        try {
            normalized = PrismPackSettingValues.normalize(definition, rawValue);
        } catch (PrismPackLoadException exception) {
            PrismMod.LOGGER.warn("Rejected invalid Prism setting {}.{}='{}': {}", packId, settingId, rawValue, exception.getMessage());
            PrismChatReporter.error(exception.code(), exception.getMessage(), "settings");
            return false;
        }

        List<PrismPackSetting> current = new ArrayList<>(settingsStore.load(pack));
        boolean changed = false;
        for (int i = 0; i < current.size(); i++) {
            PrismPackSetting setting = current.get(i);
            if (setting.definition().id().equals(settingId)) {
                if (!setting.value().equals(normalized)) {
                    current.set(i, new PrismPackSetting(definition, normalized));
                    changed = true;
                }
                break;
            }
        }
        if (!changed) {
            return true;
        }
        if (!settingsStore.write(pack, current)) {
            PrismChatReporter.error("setting_write_failed", "Could not save shader setting '" + settingId + "'", packId);
            return false;
        }
        failedCompile = null;
        if (packId.equals(configuredPackId)) {
            // Coalesce rapid UI changes (for example repeated slider/step adjustments).
            // Values are persisted/published immediately, while compilation starts only
            // after the user pauses briefly. This avoids compiling every intermediate value.
            settingsReloadNotBeforeNanos = System.nanoTime() + SETTINGS_RELOAD_DEBOUNCE_NANOS;
            reloadRequested = true;
        }
        publishSettingsSnapshot(discovery);
        PrismMod.LOGGER.info("Prism setting {}.{} changed to '{}'", packId, settingId, normalized);
        return true;
    }

    private boolean resetSettings(String packId) {
        if (packId == null) {
            return false;
        }
        Discovery discovery = discover();
        PrismPackDefinition pack = definitionForPackId(discovery, packId);
        if (pack == null) {
            PrismChatReporter.error("settings_pack_missing", "Could not find shader pack settings for '" + packId + "'", "settings");
            return false;
        }
        if (!settingsStore.reset(pack)) {
            PrismChatReporter.error("setting_reset_failed", "Could not reset shader settings", packId);
            return false;
        }
        failedCompile = null;
        if (packId.equals(configuredPackId)) {
            watchReloadNotBeforeNanos = 0L;
            settingsReloadNotBeforeNanos = 0L;
            reloadRequested = true;
        }
        publishSettingsSnapshot(discovery);
        PrismMod.LOGGER.info("Prism settings for '{}' reset to creator defaults", packId);
        return true;
    }

    private void publishDiscoverySnapshot() {
        Discovery discovery = discover();
        reportDiscoveryErrors(discovery);
        scheduleTransientDiscoveryRetry(discovery);
        Selection selection = selectActive(discovery);
        configuredPackId = selection == null ? "" : selection.packId();
        configuredPackSelector = selection == null ? "" : selection.selector();
        publishSettingsSnapshot(discovery, selection);

        PrismPackInfo currentActive = publicApi.active().orElse(null);
        String currentActiveId = currentActive == null ? "" : currentActive.id();
        publishPublicState(
                discovery,
                selection,
                currentActive,
                List.of(),
                currentActiveId,
                reloadGeneration);
    }

    private void publishSettingsSnapshot(Discovery discovery) {
        Selection selection = configuredPackSelector.isEmpty()
                ? null
                : resolveSelection(configuredPackSelector, discovery);
        publishSettingsSnapshot(discovery, selection);
    }

    private void publishSettingsSnapshot(Discovery discovery, Selection selection) {
        Map<String, List<PrismPackSetting>> byPack = new LinkedHashMap<>();
        discovery.valid().forEach((id, preferred) -> {
            PrismPackDefinition definition = selection != null
                    && selection.definition() != null
                    && id.equals(selection.packId())
                    ? selection.definition()
                    : preferred;
            byPack.put(id, settingsStore.load(definition));
        });
        settingsApi.publish(byPack);
    }

    private void publishPublicState(
            Discovery discovery,
            Selection selection,
            PrismPackInfo selectedOverride,
            List<PrismPackDiagnostic> globalDiagnostics,
            String activeId,
            long generation) {
        publicApi.publish(
                buildInfos(discovery, selection, selectedOverride, globalDiagnostics),
                activeId,
                generation,
                metadataFor(discovery, selection),
                variantsFor(discovery, selection));
    }

    /**
     * Compatibility pack list: one concrete version per pack id.
     * Shader Library 2 exposes every installed version through PrismPackApi.variants/allVariants.
     */
    private List<PrismPackInfo> buildInfos(
            Discovery discovery,
            Selection selection,
            PrismPackInfo selectedOverride,
            List<PrismPackDiagnostic> globalDiagnostics) {
        Map<String, PrismPackDefinition> compatibility = new LinkedHashMap<>(discovery.valid());
        if (selection != null && selection.definition() != null) {
            compatibility.put(selection.packId(), selection.definition());
        }

        List<PrismPackInfo> infos = new ArrayList<>();
        compatibility.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    PrismPackDefinition definition = entry.getValue();
                    if (selectedOverride != null && definition.id().equals(selectedOverride.id())) {
                        infos.add(selectedOverride);
                    } else if (selection != null
                            && definition.id().equals(selection.packId())
                            && !globalDiagnostics.isEmpty()) {
                        infos.add(info(definition, PrismPackStatus.ERROR, reloadGeneration, globalDiagnostics));
                    } else {
                        infos.add(info(definition, PrismPackStatus.DISABLED, reloadGeneration, List.of()));
                    }
                });

        appendMissingSelectedInfo(infos, selectedOverride);

        // Broken manifests still remain visible to compatibility callers and creator diagnostics.
        for (Discovered discovered : discovery.discovered()) {
            if (discovered.definition() != null) continue;
            PrismPackDiagnostic parseError = discovered.diagnostic();
            String fallbackId = "invalid." + sanitize(discovered.path().getFileName().toString());
            infos.add(new PrismPackInfo(
                    fallbackId,
                    discovered.path().getFileName().toString(),
                    "unknown",
                    PrismPackStatus.ERROR,
                    0,
                    reloadGeneration,
                    parseError == null ? globalDiagnostics : List.of(parseError)));
        }

        infos.sort(Comparator.comparing(PrismPackInfo::id));
        return List.copyOf(infos);
    }

    static void appendMissingSelectedInfo(List<PrismPackInfo> infos, PrismPackInfo selected) {
        if (selected != null && infos.stream().noneMatch(info -> info.id().equals(selected.id()))) infos.add(selected);
    }

    private static PrismPackInfo info(
            PrismPackDefinition definition,
            PrismPackStatus status,
            long generation,
            List<PrismPackDiagnostic> diagnostics) {
        return new PrismPackInfo(
                definition.id(),
                definition.name(),
                definition.version(),
                status,
                definition.programSet().orderedPrograms().size(),
                generation,
                diagnostics);
    }

    private static PrismPackDiagnostic diagnosticFrom(Exception exception) {
        if (exception instanceof PrismPackLoadException pack) {
            return error(pack.code(), pack.getMessage(), pack.source());
        }
        return error(
                "runtime_error",
                exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                "runtime");
    }

    private static PrismPackDiagnostic error(String code, String message, String source) {
        return new PrismPackDiagnostic(PrismDiagnosticSeverity.ERROR, code, message, source);
    }

    private static boolean isCacheableCompileFailure(String code) {
        return code.startsWith("shader_")
                || code.startsWith("scene_")
                || code.startsWith("pipeline_")
                || code.startsWith("graph_")
                || code.startsWith("include_")
                || code.startsWith("setting_")
                || code.startsWith("resource_");
    }

    private static String sanitize(String value) {
        String cleaned = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isBlank() ? "pack" : cleaned;
    }

    private static long packContentFingerprint(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        long hashedBytes = 0L;
        try (var stream = Files.walk(normalizedRoot)) {
            List<Path> discovered = stream
                    .filter(Files::isRegularFile)
                    .limit(MAX_FINGERPRINT_FILES + 1L)
                    .toList();
            boolean truncated = discovered.size() > MAX_FINGERPRINT_FILES;
            List<Path> files = new ArrayList<>(truncated
                    ? discovered.subList(0, MAX_FINGERPRINT_FILES)
                    : discovered);
            files.sort(Comparator.naturalOrder());
            Path realRoot = normalizedRoot.toRealPath();
            for (Path file : files) {
                Path relative = normalizedRoot.relativize(file);
                String normalized = relative.toString().replace('\\', '/');
                hash = mix(hash, normalized.hashCode());
                try {
                    long size = Files.size(file);
                    hash = mix(hash, size);
                    hash = mix(hash, Files.getLastModifiedTime(file).toMillis());
                    Path realFile = file.toRealPath();
                    boolean contained = !realFile.equals(realRoot) && realFile.startsWith(realRoot);
                    if (!contained) {
                        hash = mix(hash, 0x455343415045L); // "ESCAPE"
                    } else if (isCompilerInputFile(normalized)
                            && size <= MAX_FINGERPRINT_FILE_BYTES
                            && hashedBytes + size <= MAX_FINGERPRINT_TOTAL_BYTES) {
                        try (InputStream input = Files.newInputStream(file)) {
                            byte[] buffer = new byte[64 * 1024];
                            long fileHashed = 0L;
                            int read;
                            while ((read = input.read(buffer)) >= 0) {
                                int accepted = (int) Math.min(
                                        read,
                                        Math.min(
                                                MAX_FINGERPRINT_FILE_BYTES - fileHashed,
                                                MAX_FINGERPRINT_TOTAL_BYTES - hashedBytes - fileHashed));
                                for (int index = 0; index < accepted; index++) {
                                    hash = mix(hash, buffer[index] & 0xffL);
                                }
                                fileHashed += accepted;
                                if (accepted < read) {
                                    hash = mix(hash, 0x4c494d4954L); // "LIMIT"
                                    break;
                                }
                            }
                            hashedBytes += fileHashed;
                        }
                    } else if (isCompilerInputFile(normalized)) {
                        hash = mix(hash, 0x4c494d4954L); // "LIMIT"
                    }
                } catch (IOException ignored) {
                    hash = mix(hash, -1L);
                }
            }
            if (truncated) {
                hash = mix(hash, 0x5452554e43415445L); // "TRUNCATE"
            }
        } catch (IOException ignored) {
            return hash;
        }
        return hash;
    }

    private static long settingsFingerprint(List<PrismPackSetting> settings) {
        long hash = 0xcbf29ce484222325L;
        for (PrismPackSetting setting : settings) {
            hash = mix(hash, setting.definition().id().hashCode());
            hash = mix(hash, setting.value().hashCode());
        }
        return hash;
    }

    private static boolean isCompilerInputFile(String normalizedPath) {
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("prism.json")
                || lower.endsWith(".glsl")
                || lower.endsWith(".vsh")
                || lower.endsWith(".fsh")
                || lower.endsWith(".vert")
                || lower.endsWith(".frag")
                || lower.endsWith(".comp")
                || lower.endsWith(".inc")
                || lower.endsWith(".png")
                || lower.endsWith(".hlsl")
                || lower.endsWith(".slang");
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private void closeSampler() {
        for (var entry : sharedSamplers.entrySet()) {
            closeSamplerResource(entry.getValue(), entry.getKey());
            performanceApi.recordSamplerClosed();
        }
        sharedSamplers.clear();
    }

    private static void closeSamplerResource(GpuSampler sampler, String label) {
        try {
            sampler.close();
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.warn("Prism {} sampler cleanup failed", label, exception);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        publicApi.setReloadHandler(() -> { });
        settingsApi.setHandlers((packId, settingId, value) -> false, packId -> false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException exception) {
                PrismMod.LOGGER.debug("Prism watch service close failed", exception);
            }
            watchService = null;
        }
        if (watcher != null) {
            watcher.shutdownNow();
            watcher = null;
        }
        if (preprocessor != null) {
            preprocessor.shutdownNow();
            preprocessor = null;
        }
        cancelPendingReload();
        pipelineSessionCache.clear();
        performanceApi.recordSessionCachedPipelines(0);
        watchDirectories.clear();
        deactivate();
        failedCompile = null;
        closeSampler();
        publicApi.clear();
        settingsApi.clear();
        performanceApi.deactivate();
        gpuProfiler.close();
        PrismWorldRenderingPipeline.close();
        PrismMod.LOGGER.info("Prism shader-pack runtime stopped");
    }

    private record FailedCompile(
            String packId,
            long compileFingerprint,
            long environmentFingerprint,
            PrismPackDiagnostic diagnostic) {
        boolean matches(
                String candidatePackId,
                long candidateFingerprint,
                long candidateEnvironmentFingerprint) {
            return packId.equals(candidatePackId)
                    && compileFingerprint == candidateFingerprint
                    && environmentFingerprint == candidateEnvironmentFingerprint;
        }
    }

    private static final class PendingReload {
        final long generation;
        final Discovery discovery;
        final Selection selection;
        final PrismPackDefinition selected;
        final List<PrismPackSetting> settings;
        final long compileFingerprint;
        final GpuDevice device;
        final GpuFormat outputFormat;
        final PrismCompiledPack previousForReuse;
        final PrismPipelineCompiler.PreparationJob preparation;
        PrismPipelineCompiler.CompileSession compileSession;

        PendingReload(
                long generation,
                Discovery discovery,
                Selection selection,
                PrismPackDefinition selected,
                List<PrismPackSetting> settings,
                long compileFingerprint,
                GpuDevice device,
                GpuFormat outputFormat,
                PrismCompiledPack previousForReuse,
                PrismPipelineCompiler.PreparationJob preparation) {
            this.generation = generation;
            this.discovery = discovery;
            this.selection = selection;
            this.selected = selected;
            this.settings = List.copyOf(settings);
            this.compileFingerprint = compileFingerprint;
            this.device = device;
            this.outputFormat = outputFormat;
            this.previousForReuse = previousForReuse;
            this.preparation = preparation;
        }
    }

    private record Discovered(
            Path path,
            PrismPackDefinition definition,
            PrismPackDiagnostic diagnostic,
            boolean archive,
            boolean legacy) {
    }

    private record Discovery(
            Map<String, PrismPackDefinition> valid,
            Map<String, PrismPackDefinition> bySelector,
            List<Discovered> discovered) {
    }

    private record Selection(String selector, String packId, PrismPackDefinition definition) {
    }

}
