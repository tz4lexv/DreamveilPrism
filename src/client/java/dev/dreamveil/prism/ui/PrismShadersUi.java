package dev.dreamveil.prism.ui;

import java.util.Objects;

import dev.dreamveil.prism.pack.PrismShaderPackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.2 UI bridge for the creator shader-pack selector. */
public final class PrismShadersUi {
    private static PrismShaderPackManager manager;

    private PrismShadersUi() {
    }

    public static synchronized void initialize(PrismShaderPackManager packManager) {
        manager = Objects.requireNonNull(packManager, "packManager");
    }

    public static synchronized void close() {
        manager = null;
    }

    public static synchronized void open(Screen parent) {
        PrismShaderPackManager current = manager;
        if (current == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.setScreen(new PrismOptionsScreen(parent, current, PrismOptionsScreen.Tab.GENERAL));
        }
    }
}
