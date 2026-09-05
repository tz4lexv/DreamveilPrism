package dev.dreamveil.prism.ui;

import java.math.BigDecimal;
import java.util.List;

import dev.dreamveil.prism.api.Prism;
import dev.dreamveil.prism.api.setting.PrismPackSetting;
import dev.dreamveil.prism.api.setting.PrismPackSettingType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Generated UI for creator-declared compile-time pack settings. Prism owns controls, not visual meaning. */
public final class PrismPackSettingsScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;

    private final Screen parent;
    private final String packId;
    private List<PrismPackSetting> settings = List.of();
    private int page;
    private String notice = "";

    public PrismPackSettingsScreen(Screen parent, String packId) {
        super(Component.literal("Prism Shader Settings"));
        this.parent = parent;
        this.packId = packId;
    }

    @Override
    protected void init() {
        refresh();
        int centerX = width / 2;
        int rowWidth = Math.min(420, Math.max(180, width - 40));
        int rowX = centerX - rowWidth / 2;
        int y = 62;

        int pageSize = pageSize();
        int pageCount = Math.max(1, (settings.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pageCount - 1));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, settings.size());

        for (int i = start; i < end; i++) {
            PrismPackSetting setting = settings.get(i);
            Button button = Button.builder(Component.literal(label(setting)), pressed -> {
                        String next = nextValue(setting);
                        boolean changed = Prism.tryApi()
                                .map(api -> api.settings().set(packId, setting.definition().id(), next))
                                .orElse(false);
                        notice = changed
                                ? "Changed " + setting.definition().label() + "; Prism will apply it after a brief pause."
                                : "Could not change " + setting.definition().label();
                        refresh();
                        rebuildWidgets();
                    })
                    .bounds(rowX, y, rowWidth, ROW_HEIGHT)
                    .tooltip(Tooltip.create(tooltip(setting)))
                    .build();
            addRenderableWidget(button);
            y += ROW_HEIGHT + ROW_GAP;
        }

        int bottomY = height - 28;
        int gap = 6;
        int buttonWidth = Math.min(96, Math.max(62, (width - 28 - gap * 3) / 4));
        int total = buttonWidth * 4 + gap * 3;
        int startX = centerX - total / 2;

        Button previous = Button.builder(Component.literal("Previous"), button -> {
                    if (page > 0) {
                        page--;
                        rebuildWidgets();
                    }
                })
                .bounds(startX, bottomY, buttonWidth, 20)
                .build();
        previous.active = page > 0;
        addRenderableWidget(previous);

        Button next = Button.builder(Component.literal("Next"), button -> {
                    if (page + 1 < pageCount) {
                        page++;
                        rebuildWidgets();
                    }
                })
                .bounds(startX + buttonWidth + gap, bottomY, buttonWidth, 20)
                .build();
        next.active = page + 1 < pageCount;
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
                    boolean reset = Prism.tryApi().map(api -> api.settings().reset(packId)).orElse(false);
                    notice = reset ? "Creator defaults restored; Prism will recompile safely." : "Could not reset settings.";
                    refresh();
                    rebuildWidgets();
                })
                .bounds(startX + (buttonWidth + gap) * 2, bottomY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Restore the values declared by this shader pack")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(startX + (buttonWidth + gap) * 3, bottomY, buttonWidth, 20)
                .build());
    }

    private int pageSize() {
        int available = Math.max(72, height - 144);
        return Math.max(3, Math.min(10, available / (ROW_HEIGHT + ROW_GAP)));
    }

    private void refresh() {
        settings = Prism.tryApi().map(api -> api.settings().settings(packId)).orElse(List.of());
    }

    private static String label(PrismPackSetting setting) {
        String value = setting.definition().type() == PrismPackSettingType.BOOLEAN
                ? (setting.booleanValue() ? "On" : "Off")
                : setting.value();
        return setting.definition().label() + ": " + value;
    }

    private static Component tooltip(PrismPackSetting setting) {
        StringBuilder text = new StringBuilder(setting.definition().id());
        if (!setting.definition().description().isBlank()) {
            text.append("\n").append(setting.definition().description());
        }
        text.append("\nCompile-time setting: rapid changes are coalesced before recompilation.");
        return Component.literal(text.toString());
    }

    private static String nextValue(PrismPackSetting setting) {
        var definition = setting.definition();
        return switch (definition.type()) {
            case BOOLEAN -> Boolean.toString(!setting.booleanValue());
            case ENUM -> {
                int index = definition.values().indexOf(setting.value());
                yield definition.values().get((index + 1) % definition.values().size());
            }
            case INTEGER -> {
                int step = (int) Math.round(definition.step());
                int next = setting.integerValue() + step;
                if (next > (int) Math.round(definition.max())) {
                    next = (int) Math.round(definition.min());
                }
                yield Integer.toString(next);
            }
            case FLOAT -> {
                double next = setting.floatValue() + definition.step();
                if (next > definition.max() + 1e-9) {
                    next = definition.min();
                }
                yield BigDecimal.valueOf(next).stripTrailingZeros().toPlainString();
            }
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, Component.literal("Shader Settings"), width / 2, 20, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(packId + " | " + settings.size() + " creator settings"), width / 2, 40, 0xFFBFC7D5);
        if (settings.isEmpty()) {
            graphics.centeredText(font, Component.literal("This shader pack declares no settings."), width / 2, 88, 0xFFE5E5E5);
        }
        if (!notice.isBlank()) {
            graphics.centeredText(font, Component.literal(notice), width / 2, height - 44, 0xFFE5E5E5);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(parent);
        }
    }
}
