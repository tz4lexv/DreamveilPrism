package dev.dreamveil.prism.mixin;

import dev.dreamveil.prism.ui.PrismShadersUi;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds a Prism Shaders entry beside Done without replacing Minecraft's options layout. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToFooter(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
            index = 0)
    private LayoutElement dreamveilPrism$addShadersButton(LayoutElement originalDone) {
        if (originalDone instanceof AbstractWidget widget) {
            widget.setWidth(145);
        }

        Button shaders = Button.builder(
                        Component.literal("Shaders"),
                        button -> PrismShadersUi.open((Screen) (Object) this))
                .width(145)
                .build();

        LinearLayout row = LinearLayout.horizontal().spacing(8);
        row.addChild(shaders);
        row.addChild(originalDone);
        return row;
    }
}
