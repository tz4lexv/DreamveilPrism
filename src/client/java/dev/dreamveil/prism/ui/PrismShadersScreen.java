package dev.dreamveil.prism.ui;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.dreamveil.prism.api.Prism;
import dev.dreamveil.prism.api.pack.PrismPackVariant;
import dev.dreamveil.prism.pack.PrismShaderPackManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shader Library 2: version-aware, searchable and virtualized Prism shader library. */
public final class PrismShadersScreen extends Screen {
    private static final Component TITLE = Component.literal("Dreamveil Prism Shaders");
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int LIST_TOP = 122;
    private static final String SUPPORT_URL = "https://ko-fi.com/tz4lexv#setCreatorStatusModal";

    private final Screen parent;
    private final PrismShaderPackManager packManager;
    private final String initialSelector;
    private List<PrismPackVariant> allPacks = List.of();
    private List<PrismPackVariant> filteredPacks = List.of();
    private int scrollIndex;
    private String searchQuery = "";
    private String notice = "";
    private int listX;
    private int listWidth;
    private int visibleRows;
    private long observedLibraryRevision;

    public PrismShadersScreen(Screen parent, PrismShaderPackManager packManager) {
        super(TITLE);
        this.parent = parent;
        this.packManager = packManager;
        this.initialSelector = packManager.configuredPackSelector();
    }

    @Override
    protected void init() {
        observedLibraryRevision = packManager.libraryRevision();
        refreshPacks();
        int centerX = width / 2;
        listWidth = Math.min(560, Math.max(240, width - 48));
        listX = centerX - listWidth / 2;

        addTabs();

        Component supportLabel = Component.translatable("prism.shaders.support");
        int supportWidth = Math.min(120, Math.max(96, font.width(supportLabel) + 16));
        addRenderableWidget(Button.builder(
                        Component.literal(packManager.configuredPackSelector().isEmpty()
                                ? "Shaders: Disabled" : "Shaders: Enabled"),
                        button -> toggleShaders())
                .bounds(listX, 68, listWidth - supportWidth - 6, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Enable the selected shader pack, or return to Minecraft's vanilla renderer")))
                .build());

        // External navigation is opt-in and confirmed by Minecraft. It never changes pack state.
        addRenderableWidget(Button.builder(supportLabel,
                        ConfirmLinkScreen.confirmLink(this, SUPPORT_URL, false))
                .bounds(listX + listWidth - supportWidth, 68, supportWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("prism.shaders.support.tooltip")))
                .build());

        EditBox search = new EditBox(font, listX, 94, listWidth, 20, Component.literal("Search shaders"));
        search.setHint(Component.literal("Search shaders by name, version, author or id..."));
        search.setMaxLength(96);
        search.setValue(searchQuery);
        search.setResponder(value -> {
            if (!value.equals(searchQuery)) {
                searchQuery = value;
                scrollIndex = 0;
                rebuildWidgets();
            }
        });
        addRenderableWidget(search);

        int firstFooterY = height - 52;
        int secondFooterY = height - 28;
        int listBottom = Math.max(LIST_TOP + ROW_HEIGHT, firstFooterY - 12);
        visibleRows = Math.max(2, (listBottom - LIST_TOP) / (ROW_HEIGHT + ROW_GAP));

        int packRows = Math.max(1, visibleRows);
        int maxScroll = Math.max(0, filteredPacks.size() - packRows);
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll));
        int y = LIST_TOP;
        int end = Math.min(filteredPacks.size(), scrollIndex + packRows);
        String configuredSelector = packManager.configuredPackSelector();
        String activeSelector = packManager.activePackSelector();

        for (int index = scrollIndex; index < end; index++) {
            PrismPackVariant pack = filteredPacks.get(index);
            Button button = Button.builder(
                            Component.literal(packLabel(pack, configuredSelector, activeSelector)),
                            pressed -> {
                                if (packManager.selectPackVersion(pack.selector())) {
                                    notice = "Selected " + pack.name() + " " + pack.version()
                                            + ". It will apply on the next render frame.";
                                    refreshPacks();
                                    rebuildWidgets();
                                } else {
                                    notice = "Could not select " + pack.selector();
                                }
                            })
                    .bounds(listX, y, listWidth - 8, ROW_HEIGHT)
                    .tooltip(Tooltip.create(packTooltip(pack)))
                    .build();
            button.active = !pack.hasErrors();
            addRenderableWidget(button);
            y += ROW_HEIGHT + ROW_GAP;
        }

        int gap = 6;
        int upperWidth = (listWidth - gap) / 2;

        addRenderableWidget(Button.builder(Component.literal("Open Shader Pack Folder..."), button -> {
                    if (packManager.openPacksFolder()) notice = "Opened shaderpacks folder: " + packManager.packsRoot();
                    else notice = "Could not open shaderpacks folder. Path: " + packManager.packsRoot();
                })
                .bounds(listX, firstFooterY, upperWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Open the shaderpacks folder for this Minecraft instance")))
                .build());

        String configuredPack = packManager.configuredPackId();
        Button settingsButton = Button.builder(Component.literal("Shader Pack Settings..."), button -> {
                    if (minecraft != null && minecraft.gui != null && !configuredPack.isEmpty()) {
                        minecraft.gui.setScreen(new PrismPackSettingsScreen(this, configuredPack));
                    }
                })
                .bounds(listX + upperWidth + gap, firstFooterY, upperWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Creator-declared settings for the selected pack version")))
                .build();
        settingsButton.active = !configuredPack.isEmpty() && Prism.tryApi()
                .map(api -> !api.settings().settings(configuredPack).isEmpty()).orElse(false);
        addRenderableWidget(settingsButton);

        int lowerWidth = (listWidth - gap * 2) / 3;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> cancelAndClose())
                .bounds(listX, secondFooterY, lowerWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Restore the shader selection that was active when this screen opened")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Apply"), button -> {
                    packManager.refreshDiscoveryForUi();
                    packManager.requestReload();
                    notice = "Applying shader configuration; the last-known-good generation stays active until ready.";
                    rebuildWidgets();
                })
                .bounds(listX + lowerWidth + gap, secondFooterY, lowerWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Rescan and compile the selected shader pack transactionally")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
                    packManager.refreshDiscoveryForUi();
                    packManager.requestReload();
                    onClose();
                })
                .bounds(listX + (lowerWidth + gap) * 2, secondFooterY,
                        listWidth - (lowerWidth + gap) * 2, 20)
                .build());
    }

    private void toggleShaders() {
        String configured = packManager.configuredPackSelector();
        if (!configured.isEmpty()) {
            if (packManager.selectPack(null)) notice = "Shaders disabled; vanilla rendering selected.";
        } else {
            String candidate = !initialSelector.isEmpty() ? initialSelector : allPacks.stream()
                    .filter(pack -> !pack.hasErrors())
                    .sorted(Comparator.comparing(PrismPackVariant::preferred).reversed())
                    .map(PrismPackVariant::selector)
                    .findFirst().orElse("");
            if (candidate.isEmpty()) notice = "No compatible shader pack is installed.";
            else if (packManager.selectPackVersion(candidate)) notice = "Shaders enabled: " + candidate;
        }
        refreshPacks();
        rebuildWidgets();
    }

    private void cancelAndClose() {
        boolean restored = initialSelector.isEmpty()
                ? packManager.selectPack(null)
                : packManager.selectPackVersion(initialSelector);
        if (restored) packManager.requestReload();
        onClose();
    }

    private void addTabs() {
        int panelLeft = Math.max(16, width / 2 - 340);
        int panelRight = Math.min(width - 16, width / 2 + 340);
        int gap = 4;
        int count = PrismOptionsScreen.Tab.values().length;
        int tabWidth = Math.max(1, (panelRight - panelLeft - gap * (count - 1)) / count);
        int total = tabWidth * count + gap * (count - 1);
        int x = width / 2 - total / 2;
        for (PrismOptionsScreen.Tab tab : PrismOptionsScreen.Tab.values()) {
            Button button = Button.builder(Component.literal(tab.label), pressed -> {
                        if (minecraft == null || minecraft.gui == null) return;
                        if (tab != PrismOptionsScreen.Tab.SHADER_PACKS) {
                            minecraft.gui.setScreen(new PrismOptionsScreen(parent, packManager, tab));
                        }
                    })
                    .bounds(x, 12, tabWidth, 20)
                    .build();
            button.active = tab != PrismOptionsScreen.Tab.SHADER_PACKS;
            addRenderableWidget(button);
            x += tabWidth + gap;
        }
    }

    @Override
    public void tick() {
        super.tick();
        long revision = packManager.libraryRevision();
        if (revision != observedLibraryRevision && packManager.refreshDiscoveryForUiIfReady()) {
            observedLibraryRevision = revision;
            refreshPacks();
            notice = "Shader library updated.";
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= LIST_TOP && mouseY <= height - 66) {
            int packRows = Math.max(1, visibleRows);
            int maxScroll = Math.max(0, filteredPacks.size() - packRows);
            int old = scrollIndex;
            if (scrollY > 0.0) scrollIndex = Math.max(0, scrollIndex - Math.max(1, (int)Math.ceil(scrollY)));
            if (scrollY < 0.0) scrollIndex = Math.min(maxScroll, scrollIndex + Math.max(1, (int)Math.ceil(-scrollY)));
            if (old != scrollIndex) {
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void refreshPacks() {
        allPacks = Prism.tryApi().map(api -> api.packs().allVariants().stream()
                .sorted((a, b) -> {
                    int name = String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
                    if (name != 0) return name;
                    int id = a.id().compareToIgnoreCase(b.id());
                    if (id != 0) return id;
                    return PrismShaderPackManager.comparePackVersions(b.version(), a.version());
                })
                .toList()).orElse(List.of());

        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            filteredPacks = allPacks;
        } else {
            filteredPacks = allPacks.stream().filter(pack -> {
                var metadata = pack.metadata();
                return pack.name().toLowerCase(Locale.ROOT).contains(query)
                        || pack.id().toLowerCase(Locale.ROOT).contains(query)
                        || pack.version().toLowerCase(Locale.ROOT).contains(query)
                        || metadata.author().toLowerCase(Locale.ROOT).contains(query)
                        || metadata.description().toLowerCase(Locale.ROOT).contains(query)
                        || metadata.sourceName().toLowerCase(Locale.ROOT).contains(query);
            }).toList();
        }
    }

    private static String packLabel(PrismPackVariant pack, String configuredSelector, String activeSelector) {
        String selector = pack.selector();
        String prefix = selector.equals(activeSelector) ? "● "
                : selector.equals(configuredSelector) ? "▶ "
                : pack.preferred() ? "★ "
                : "";
        boolean incompatible = pack.diagnostics().stream()
                .anyMatch(diagnostic -> "pack_incompatible".equals(diagnostic.code()));
        String suffix = incompatible ? "  [INCOMPATIBLE]"
                : pack.hasErrors() ? "  [ERROR]"
                : pack.preferred() ? "  [latest]"
                : "  [installed]";
        return prefix + pack.name() + " " + pack.version() + suffix;
    }

    private static Component packTooltip(PrismPackVariant pack) {
        var metadata = pack.metadata();
        StringBuilder text = new StringBuilder(pack.selector());
        if (!metadata.author().isBlank()) text.append("\nby ").append(metadata.author());
        if (!metadata.description().isBlank()) text.append("\n").append(metadata.description());
        if (!metadata.sourceName().isBlank()) {
            text.append("\nSource: ").append(metadata.sourceName())
                    .append(metadata.archive() ? " (ZIP)" : " (folder)");
        }
        if (pack.preferred()) text.append("\nLatest/preferred installed version");
        if (pack.selected()) text.append("\nConfigured version");
        if (!pack.diagnostics().isEmpty()) {
            var diagnostic = pack.diagnostics().get(0);
            text.append("\n").append(diagnostic.code()).append(": ").append(diagnostic.message());
        }
        return Component.literal(text.toString());
    }


    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int panelLeft = Math.max(16, width / 2 - 340);
        int panelRight = Math.min(width - 16, width / 2 + 340);
        int gap = 4;
        int count = PrismOptionsScreen.Tab.values().length;
        int tabWidth = Math.max(1, (panelRight - panelLeft - gap * (count - 1)) / count);
        int total = tabWidth * count + gap * (count - 1);
        int selectedX = width / 2 - total / 2
                + PrismOptionsScreen.Tab.SHADER_PACKS.ordinal() * (tabWidth + gap);
        graphics.fill(selectedX + 3, 33, selectedX + tabWidth - 3, 35, 0xFF55E7D7);
        graphics.centeredText(font, TITLE, width / 2, 42, 0xFFFFFFFF);
        String configured = packManager.configuredPackSelector();
        String active = packManager.activePackSelector();
        int packIds = (int) allPacks.stream().map(PrismPackVariant::id).distinct().count();
        String selectedLabel = configured.isEmpty() ? "Off / Vanilla" : configured;
        String activeLabel = active.isEmpty() ? "Vanilla" : active;
        String activation = configured.equals(active) || (configured.isEmpty() && active.isEmpty())
                ? ""
                : " | Active: " + activeLabel + " (candidate pending/failed)";
        graphics.centeredText(font,
                Component.literal("Selected: " + selectedLabel + activation
                        + " | Packs: " + packIds
                        + " | Versions: " + allPacks.size()
                        + " | Showing: " + filteredPacks.size()),
                width / 2, 56, 0xFFBFC7D5);

        int packRows = Math.max(1, visibleRows);
        int maxScroll = Math.max(0, filteredPacks.size() - packRows);
        if (maxScroll > 0) {
            int trackX = listX + listWidth - 4;
            int trackTop = LIST_TOP;
            int trackBottom = Math.max(trackTop + 12, height - 72);
            graphics.fill(trackX, trackTop, trackX + 3, trackBottom, 0x553A3A3A);
            int trackHeight = trackBottom - trackTop;
            int thumbHeight = Math.max(12, (int)Math.round(trackHeight * Math.min(1.0, (double)packRows / filteredPacks.size())));
            int thumbTravel = Math.max(0, trackHeight - thumbHeight);
            int thumbY = trackTop + (maxScroll == 0 ? 0 : (int)Math.round((double)scrollIndex / maxScroll * thumbTravel));
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFD0D0D0);
        }

        if (!notice.isBlank()) {
            graphics.centeredText(font, Component.literal(notice), width / 2, height - 68, 0xFFE5E5E5);
        } else if (allPacks.isEmpty()) {
            graphics.centeredText(font,
                    Component.literal("Drop Prism folders or ZIPs into .minecraft/shaderpacks"),
                    width / 2, 116, 0xFFE5E5E5);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.gui != null) minecraft.gui.setScreen(parent);
    }
}
