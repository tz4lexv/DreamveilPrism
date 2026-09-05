package dev.dreamveil.prism.ui;

import java.util.ArrayList;
import java.util.List;

import dev.dreamveil.prism.api.Prism;
import dev.dreamveil.prism.api.PrismApi;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.pack.PrismShaderPackManager;
import dev.dreamveil.prism.pack.PrismClientConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native-style tabbed control center for Prism loader state and creator diagnostics. */
public final class PrismOptionsScreen extends Screen {
    enum Tab {
        GENERAL("General"),
        QUALITY("Quality"),
        PERFORMANCE("Performance"),
        ADVANCED("Advanced"),
        SHADER_PACKS("Shader Packs...");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int TAB_Y = 12;
    private static final int CONTENT_TOP = 62;
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final PrismShaderPackManager packManager;
    private final Tab selectedTab;
    private final List<StatusRow> rows = new ArrayList<>();
    private String notice = "";
    private int panelLeft;
    private int panelRight;
    private int scrollOffset;
    private int visibleRows;

    public PrismOptionsScreen(Screen parent, PrismShaderPackManager packManager, Tab selectedTab) {
        super(Component.literal("Dreamveil Prism"));
        this.parent = parent;
        this.packManager = packManager;
        this.selectedTab = selectedTab == null ? Tab.GENERAL : selectedTab;
    }

    @Override
    protected void init() {
        rows.clear();
        panelLeft = Math.max(16, width / 2 - 340);
        panelRight = Math.min(width - 16, width / 2 + 340);
        visibleRows = Math.max(1, (height - 54 - CONTENT_TOP) / ROW_HEIGHT);
        addTabs();

        switch (selectedTab) {
            case GENERAL -> initGeneral();
            case QUALITY -> initQuality();
            case PERFORMANCE -> initPerformance();
            case ADVANCED -> initAdvanced();
            case SHADER_PACKS -> openShaderPacks();
        }

        addRenderableWidget(Button.builder(Component.literal("Apply"), button -> applyChanges())
                .bounds(width / 2 - 154, height - 30, 150, 20)
                .tooltip(Tooltip.create(Component.literal("Rescan and compile the selected pack transactionally")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 + 4, height - 30, 150, 20)
                .build());
    }

    private void addTabs() {
        int gap = 4;
        int count = Tab.values().length;
        int available = panelRight - panelLeft;
        int tabWidth = Math.max(1, (available - gap * (count - 1)) / count);
        int total = tabWidth * count + gap * (count - 1);
        int x = width / 2 - total / 2;
        for (Tab tab : Tab.values()) {
            Button button = Button.builder(Component.literal(tab.label), pressed -> switchTab(tab))
                    .bounds(x, TAB_Y, tabWidth, 20)
                    .build();
            button.active = tab != selectedTab;
            addRenderableWidget(button);
            x += tabWidth + gap;
        }
    }

    private void switchTab(Tab tab) {
        if (minecraft == null || minecraft.gui == null || tab == selectedTab) return;
        if (tab == Tab.SHADER_PACKS) {
            minecraft.gui.setScreen(new PrismShadersScreen(parent, packManager));
        } else {
            minecraft.gui.setScreen(new PrismOptionsScreen(parent, packManager, tab));
        }
    }

    private void openShaderPacks() {
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(new PrismShadersScreen(parent, packManager));
        }
    }

    private void applyChanges() {
        if (minecraft != null) minecraft.options.save();
        packManager.refreshDiscoveryForUi();
        packManager.requestReload();
        notice = "Applying shader configuration; the last-known-good generation stays active until ready.";
    }

    private void initGeneral() {
        String configured = packManager.configuredPackSelector();
        String active = packManager.activePackSelector();
        addActionRow("Shader Pack", configured.isEmpty() ? "Off / Vanilla" : configured,
                () -> switchTab(Tab.SHADER_PACKS), "Browse, select and version installed shader packs");
        addStatusRow("Active Generation", active.isEmpty() ? "Vanilla renderer" : active);
        addActionRow("Shader Folder", packManager.packsRoot().getFileName().toString(), () -> {
            notice = packManager.openPacksFolder()
                    ? "Opened the shaderpacks folder."
                    : "Could not open: " + packManager.packsRoot();
        }, "Open the shaderpacks folder for this Minecraft instance");
        if (minecraft != null) addOptionsGrid(List.of(
                minecraft.options.gamma(),
                minecraft.options.fov(),
                minecraft.options.guiScale(),
                minecraft.options.fullscreen(),
                minecraft.options.enableVsync(),
                minecraft.options.framerateLimit(),
                minecraft.options.bobView(),
                minecraft.options.inactivityFpsLimit()));
    }

    private void initQuality() {
        String configuredPack = packManager.configuredPackId();
        PrismApi api = Prism.tryApi().orElse(null);
        boolean hasSettings = api != null && !configuredPack.isEmpty()
                && !api.settings().settings(configuredPack).isEmpty();
        addActionRow("Pack Quality Settings", hasSettings ? "Customize" : "No creator settings",
                () -> {
                    if (minecraft != null && minecraft.gui != null && hasSettings) {
                        minecraft.gui.setScreen(new PrismPackSettingsScreen(this, configuredPack));
                    }
                }, "Settings declared and validated by the selected shader pack", hasSettings);
        if (minecraft != null) addOptionsGrid(List.of(
                minecraft.options.graphicsPreset(),
                minecraft.options.cloudStatus(),
                minecraft.options.weatherRadius(),
                minecraft.options.cutoutLeaves(),
                minecraft.options.particles(),
                minecraft.options.ambientOcclusion(),
                minecraft.options.biomeBlendRadius(),
                minecraft.options.entityDistanceScaling(),
                minecraft.options.entityShadows(),
                minecraft.options.vignette(),
                minecraft.options.textureFiltering(),
                minecraft.options.mipmapLevels()));
        addStatusRow("Deferred scene data", support(api, PrismCapability.SCENE_GBUFFER_ATTACHMENTS,
                "Real scene MRT / G-buffer"));
    }

    private void initPerformance() {
        PrismClientConfig config = PrismClientConfig.get();
        addConfigToggle("Built-in Shadows", config.builtInShadows(), value -> config.setBuiltInShadows(value),
                "Real directional shadow replay. Restart required after changing it.");
        addActionRow("Shadow Quality", config.shadowQuality(), () -> {
            config.setShadowQuality("quality".equals(config.shadowQuality()) ? "balanced" : "quality");
            notice = "Shadow quality saved; restart Minecraft to rebuild the cascade resources.";
            rebuildWidgets();
        }, "Balanced uses one cascade; Quality uses two. Restart required.");
        if (minecraft != null) addOptionsGrid(List.of(
                minecraft.options.renderDistance(),
                minecraft.options.simulationDistance(),
                minecraft.options.cloudRange(),
                minecraft.options.weatherRadius(),
                minecraft.options.particles(),
                minecraft.options.entityDistanceScaling(),
                minecraft.options.entityShadows(),
                minecraft.options.vignette(),
                minecraft.options.ambientOcclusion(),
                minecraft.options.improvedTransparency(),
                minecraft.options.chunkSectionFadeInTime(),
                minecraft.options.prioritizeChunkUpdates()));
        addActionRow("Profiler", "Open live CPU/GPU metrics", () -> {
            if (minecraft != null && minecraft.gui != null) {
                minecraft.gui.setScreen(new PrismPerformanceScreen(this));
            }
        }, "Inspect pass timings, pipeline cache and GPU resource lifetime");
    }

    private void initAdvanced() {
        PrismApi api = Prism.tryApi().orElse(null);
        if (api == null) {
            addStatusRow("Runtime", "Not initialized");
        } else {
            addStatusRow("Prism", api.prismVersion());
            addStatusRow("Creator API", api.apiVersion().toString());
            addStatusRow("Minecraft", api.minecraftVersion());
            addStatusRow("Capabilities", api.capabilities().available().size() + " negotiated");
            addStatusRow("Storage / Compute", support(api, PrismCapability.STORAGE_IMAGES,
                    "Vulkan descriptors + barriers"));
        }
        PrismClientConfig config = PrismClientConfig.get();
        addConfigToggle("Shader Hot Reload", config.shaderHotReload(), value -> config.setShaderHotReload(value),
                "Watch pack files and transactionally compile changes in the background");
        addConfigToggle("Dynamic Resolution", config.dynamicResolution(), value -> config.setDynamicResolution(value),
                "Allow a pack's declared adaptive-resolution policy. Apply reloads the pack.");
        addConfigToggle("GPU Profiling", config.gpuProfiling(), value -> config.setGpuProfiling(value),
                "Use asynchronous Vulkan timestamp queries for Prism-owned passes");
        addActionRow("Feature Warmup", config.featureWarmupPerFrame() + " pipeline/frame", () -> {
            int next = config.featureWarmupPerFrame() >= 4 ? 1 : config.featureWarmupPerFrame() * 2;
            config.setFeatureWarmupPerFrame(next);
            notice = "Feature pipeline warmup budget changed. Lower values reduce compilation hitches.";
            rebuildWidgets();
        }, "How many queued entity/block-entity pipeline variants compile per frame (1, 2 or 4)");
        if (minecraft != null) addOptionsGrid(List.of(
                minecraft.options.preferredGraphicsBackend(),
                minecraft.options.textureFiltering(),
                minecraft.options.maxAnisotropyBit(),
                minecraft.options.mipmapLevels(),
                minecraft.options.improvedTransparency(),
                minecraft.options.prioritizeChunkUpdates()));
        addStatusRow("Persistent Mapping", "Minecraft/Vulkan backend managed");
        addStatusRow("CPU Render-Ahead", "Minecraft/Vulkan backend managed");
    }

    private void addStatusRow(String label, String value) {
        rows.add(new StatusRow(label, value, rows.size()));
    }

    private void addConfigToggle(
            String label,
            boolean current,
            java.util.function.Consumer<Boolean> setter,
            String tooltip) {
        addActionRow(label, current ? "Enabled" : "Disabled", () -> {
            setter.accept(!current);
            notice = label + " changed. Press Apply when a shader-generation reload is required.";
            rebuildWidgets();
        }, tooltip);
    }

    private void addOptionsGrid(List<OptionInstance<?>> options) {
        if (minecraft == null || options.isEmpty()) return;
        int gap = 6;
        int cellWidth = Math.max(90, (panelRight - panelLeft - gap) / 2);
        for (int index = 0; index < options.size(); index += 2) {
            int row = rows.size();
            rows.add(new StatusRow("", "", row));
            if (!rowVisible(row)) continue;
            int y = rowY(row);
            AbstractWidget left = options.get(index).createButton(
                    minecraft.options, panelLeft, y, cellWidth);
            addRenderableWidget(left);
            if (index + 1 < options.size()) {
                AbstractWidget right = options.get(index + 1).createButton(
                        minecraft.options, panelLeft + cellWidth + gap, y, cellWidth);
                addRenderableWidget(right);
            }
        }
    }

    private void addActionRow(String label, String value, Runnable action, String tooltip) {
        addActionRow(label, value, action, tooltip, true);
    }

    private void addActionRow(String label, String value, Runnable action, String tooltip, boolean active) {
        int index = rows.size();
        rows.add(new StatusRow(label, "", index));
        if (!rowVisible(index)) return;
        int buttonWidth = Math.min(300, Math.max(180, (panelRight - panelLeft) / 2));
        Button button = Button.builder(Component.literal(value), pressed -> action.run())
                .bounds(panelRight - buttonWidth, rowY(index), buttonWidth, 20)
                .tooltip(Tooltip.create(Component.literal(tooltip)))
                .build();
        button.active = active;
        addRenderableWidget(button);
    }

    private boolean rowVisible(int index) {
        return index >= scrollOffset && index < scrollOffset + visibleRows;
    }

    private int rowY(int index) {
        return CONTENT_TOP + (index - scrollOffset) * ROW_HEIGHT;
    }

    private static String support(PrismApi api, PrismCapability capability, String available) {
        return api != null && api.capabilities().supports(capability) ? available : "Unavailable";
    }

    private static String dimensions(PrismApi api) {
        if (api == null) return "Unavailable";
        boolean cube = api.capabilities().supports(PrismCapability.CUBEMAP_TEXTURES);
        boolean array = api.capabilities().supports(PrismCapability.TEXTURE_ARRAYS);
        boolean volume = api.capabilities().supports(PrismCapability.TEXTURE_3D);
        return cube && array && volume ? "Cube + 2D array + 3D" : cube ? "Cubemap" : "2D only";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int selectedIndex = selectedTab.ordinal();
        int gap = 4;
        int count = Tab.values().length;
        int available = panelRight - panelLeft;
        int tabWidth = Math.max(1, (available - gap * (count - 1)) / count);
        int total = tabWidth * count + gap * (count - 1);
        int selectedX = width / 2 - total / 2 + selectedIndex * (tabWidth + gap);
        graphics.fill(selectedX + 3, TAB_Y + 21, selectedX + tabWidth - 3, TAB_Y + 23, 0xFF55E7D7);

        graphics.centeredText(font, Component.literal("Dreamveil Prism • " + selectedTab.label),
                width / 2, 42, 0xFFFFFFFF);
        for (StatusRow row : rows) {
            if (!rowVisible(row.index)) continue;
            int displayIndex = row.index - scrollOffset;
            int y = CONTENT_TOP + displayIndex * ROW_HEIGHT + 6;
            graphics.text(font, Component.literal(row.label), panelLeft + 8, y, 0xFFE8E8E8, true);
            if (!row.value.isEmpty()) {
                int valueWidth = font.width(row.value);
                graphics.text(font, Component.literal(row.value), panelRight - valueWidth - 8, y,
                        0xFFB9C6D8, true);
            }
            graphics.fill(panelLeft, CONTENT_TOP + (displayIndex + 1) * ROW_HEIGHT - 3,
                    panelRight, CONTENT_TOP + (displayIndex + 1) * ROW_HEIGHT - 2, 0x333F3F3F);
        }
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        if (maxScroll > 0) {
            int trackX = panelRight + 5;
            int trackHeight = visibleRows * ROW_HEIGHT;
            graphics.fill(trackX, CONTENT_TOP, trackX + 3, CONTENT_TOP + trackHeight, 0x553A3A3A);
            int thumbHeight = Math.max(12,
                    (int)Math.round(trackHeight * Math.min(1.0, (double)visibleRows / rows.size())));
            int thumbTravel = Math.max(0, trackHeight - thumbHeight);
            int thumbY = CONTENT_TOP + (int)Math.round((double)scrollOffset / maxScroll * thumbTravel);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFD0D0D0);
        }
        if (!notice.isBlank()) {
            graphics.centeredText(font, Component.literal(notice), width / 2, height - 46, 0xFFE7D58A);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelLeft && mouseX <= panelRight + 8
                && mouseY >= CONTENT_TOP && mouseY < height - 48) {
            int maxScroll = Math.max(0, rows.size() - visibleRows);
            int old = scrollOffset;
            if (scrollY > 0.0) scrollOffset = Math.max(0,
                    scrollOffset - Math.max(1, (int)Math.ceil(scrollY)));
            if (scrollY < 0.0) scrollOffset = Math.min(maxScroll,
                    scrollOffset + Math.max(1, (int)Math.ceil(-scrollY)));
            if (old != scrollOffset) {
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.options.save();
        if (minecraft != null && minecraft.gui != null) minecraft.gui.setScreen(parent);
    }

    private record StatusRow(String label, String value, int index) {}
}
