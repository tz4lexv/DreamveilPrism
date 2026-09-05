package dev.dreamveil.prism;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;

public final class PrismMod implements ModInitializer {
    public static final String MOD_ID = "dreamveil-prism";
    public static final String VERSION = "0.14.0-beta.1";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Dreamveil Prism {} common bootstrap initialized", VERSION);
    }
}
