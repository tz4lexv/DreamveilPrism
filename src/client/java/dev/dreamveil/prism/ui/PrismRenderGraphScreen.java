package dev.dreamveil.prism.ui;

import java.util.List;

import dev.dreamveil.prism.api.Prism;
import dev.dreamveil.prism.api.performance.PrismGraphDiagnosticsSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Zero-capture creator view of the immutable graph plan installed with the active generation. */
public final class PrismRenderGraphScreen extends Screen {
    private static final int HEADER_BOTTOM = 70;
    private static final int ROW_HEIGHT = 14;

    private enum Section {
        PASSES("Passes"),
        RESOURCES("Resources"),
        BARRIERS("Barriers");

        private final String label;

        Section(String label) {
            this.label = label;
        }
    }

    private final Screen parent;
    private Section section = Section.PASSES;
    private int scrollIndex;

    public PrismRenderGraphScreen(Screen parent) {
        super(Component.literal("Dreamveil Prism Render Graph"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(600, Math.max(270, width - 36));
        int panelLeft = width / 2 - panelWidth / 2;
        int gap = 4;
        int tabWidth = (panelWidth - gap * 2) / 3;
        int x = panelLeft;
        for (Section candidate : Section.values()) {
            Button button = Button.builder(Component.literal(candidate.label), pressed -> {
                        section = candidate;
                        scrollIndex = 0;
                        rebuildWidgets();
                    })
                    .bounds(x, 38, tabWidth, 20)
                    .build();
            button.active = candidate != section;
            addRenderableWidget(button);
            x += tabWidth + gap;
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20)
                .build());
        clampScroll(snapshot());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= HEADER_BOTTOM && mouseY <= height - 36) {
            PrismGraphDiagnosticsSnapshot graph = snapshot();
            int max = maxScroll(graph);
            int previous = scrollIndex;
            if (scrollY > 0.0) scrollIndex = Math.max(0, scrollIndex - Math.max(1, (int)Math.ceil(scrollY)));
            if (scrollY < 0.0) scrollIndex = Math.min(max, scrollIndex + Math.max(1, (int)Math.ceil(-scrollY)));
            if (previous != scrollIndex) return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        PrismGraphDiagnosticsSnapshot graph = snapshot();
        graphics.centeredText(font, Component.literal("Dreamveil Prism Render Graph"), width / 2, 14, 0xFFFFFFFF);
        if (!graph.available()) {
            graphics.centeredText(font, Component.literal("No active Prism graph."), width / 2, 82, 0xFFE5E5E5);
            return;
        }
        graphics.centeredText(font, Component.literal(
                        graph.packId() + " | " + graph.passes().size() + " passes | "
                                + graph.resources().size() + " resources | " + graph.transitions().size()
                                + " transitions | " + graph.aliasSavings() + " alias savings"),
                width / 2, 26, 0xFFBFC7D5);

        List<String> rows = rows(graph);
        int visible = visibleRows();
        int end = Math.min(rows.size(), scrollIndex + visible);
        int panelLeft = Math.max(18, width / 2 - 330);
        int panelRight = Math.min(width - 18, width / 2 + 330);
        int y = HEADER_BOTTOM;
        for (int index = scrollIndex; index < end; index++) {
            String value = fit(rows.get(index), Math.max(24, (panelRight - panelLeft) / 6));
            graphics.text(font, Component.literal(value), panelLeft, y, 0xFFE8E8E8, true);
            y += ROW_HEIGHT;
        }

        int max = Math.max(0, rows.size() - visible);
        if (max > 0) {
            int trackTop = HEADER_BOTTOM;
            int trackBottom = Math.max(trackTop + 12, height - 38);
            int trackHeight = trackBottom - trackTop;
            int thumbHeight = Math.max(12, (int)Math.round(trackHeight * (double)visible / rows.size()));
            int thumbY = trackTop + (int)Math.round((double)scrollIndex / max * (trackHeight - thumbHeight));
            graphics.fill(panelRight - 3, trackTop, panelRight, trackBottom, 0x553A3A3A);
            graphics.fill(panelRight - 3, thumbY, panelRight, thumbY + thumbHeight, 0xFFD0D0D0);
        }
    }

    private List<String> rows(PrismGraphDiagnosticsSnapshot graph) {
        return switch (section) {
            case PASSES -> graph.passes().stream()
                    .flatMap(pass -> java.util.stream.Stream.of(pass.executionType() + "  " + pass.name()
                            + "  [in " + pass.incomingTransitions()
                            + " / out " + pass.outgoingTransitions() + "]",
                            "    after: " + (pass.dependencies().isEmpty() ? "(none)" : String.join(", ", pass.dependencies()))))
                    .toList();
            case RESOURCES -> graph.resources().stream()
                    .map(resource -> resource.resourceType() + "  " + resource.name()
                            + (resource.imported() ? "  [imported]"
                            : "  [life " + resource.firstUsePass() + ".." + resource.lastUsePass()
                                    + " / slot " + resource.physicalSlot() + "]")
                            + "  " + resource.description())
                    .toList();
            case BARRIERS -> graph.transitions().stream()
                    .map(edge -> (edge.requiresSynchronization() ? "BARRIER " : "STATE ")
                            + edge.hazard() + "  " + edge.resourceName() + "  "
                            + edge.fromPass() + " (" + edge.fromExecutionType() + "/" + edge.fromState() + ") -> "
                            + edge.toPass() + " (" + edge.toExecutionType() + "/" + edge.toState() + ")")
                    .toList();
        };
    }

    private int visibleRows() {
        return Math.max(1, (height - HEADER_BOTTOM - 38) / ROW_HEIGHT);
    }

    private int maxScroll(PrismGraphDiagnosticsSnapshot graph) {
        return Math.max(0, rows(graph).size() - visibleRows());
    }

    private void clampScroll(PrismGraphDiagnosticsSnapshot graph) {
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll(graph)));
    }

    private PrismGraphDiagnosticsSnapshot snapshot() {
        return Prism.tryApi()
                .map(api -> api.performance().graphSnapshot())
                .orElse(PrismGraphDiagnosticsSnapshot.EMPTY);
    }

    private static String fit(String value, int characters) {
        if (value.length() <= characters) return value;
        return value.substring(0, Math.max(1, characters - 1)) + "…";
    }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.gui != null) minecraft.gui.setScreen(parent);
    }
}
