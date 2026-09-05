package dev.dreamveil.prism.client;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.pack.PrismPackDiagnostic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Small client-side error surface for creator/runtime failures.
 *
 * Errors are still logged in full. The chat copy is intentionally compact,
 * rate-limited and bounded so a broken shader cannot spam every frame.
 */
public final class PrismChatReporter {
    private static final long DEDUPE_NANOS = 10_000_000_000L;
    private static final int MAX_PENDING = 32;
    private static final int MAX_KEYS = 128;
    private static final int MAX_MESSAGE_CHARS = 220;

    private static final Map<String, Long> LAST_REPORTED = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PendingMessage> PENDING = new ConcurrentLinkedQueue<>();

    private PrismChatReporter() {
    }

    public static void error(PrismPackDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        error(diagnostic.code(), diagnostic.message(), diagnostic.source());
    }

    public static void error(String code, String message, String source) {
        String normalizedCode = compact(code == null || code.isBlank() ? "error" : code, 48);
        String normalizedMessage = compact(message == null || message.isBlank() ? "Unknown Prism error" : message, MAX_MESSAGE_CHARS);
        String normalizedSource = compact(source == null ? "" : source, 80);
        String key = normalizedCode + '\u0000' + normalizedMessage + '\u0000' + normalizedSource;

        long now = System.nanoTime();
        Long previous = LAST_REPORTED.put(key, now);
        if (previous != null && now - previous < DEDUPE_NANOS) {
            return;
        }
        if (LAST_REPORTED.size() > MAX_KEYS) {
            long cutoff = now - DEDUPE_NANOS;
            LAST_REPORTED.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            if (LAST_REPORTED.size() > MAX_KEYS) {
                LAST_REPORTED.clear();
                LAST_REPORTED.put(key, now);
            }
        }

        PendingMessage pending = new PendingMessage(normalizedCode, normalizedMessage, normalizedSource);
        if (!deliver(pending)) {
            while (PENDING.size() >= MAX_PENDING) {
                PENDING.poll();
            }
            PENDING.offer(pending);
        }
    }

    /** Called from Prism's world render callback when Minecraft chat is ready. */
    public static void flushPending() {
        for (int i = 0; i < MAX_PENDING; i++) {
            PendingMessage message = PENDING.poll();
            if (message == null) {
                return;
            }
            if (!deliver(message)) {
                PENDING.offer(message);
                return;
            }
        }
    }

    public static void clear() {
        PENDING.clear();
        LAST_REPORTED.clear();
    }

    private static boolean deliver(PendingMessage pending) {
        Minecraft minecraft;
        try {
            minecraft = Minecraft.getInstance();
        } catch (RuntimeException exception) {
            return false;
        }
        if (minecraft == null || minecraft.player == null || minecraft.gui == null || minecraft.gui.hud == null) {
            return false;
        }

        try {
            minecraft.execute(() -> {
                try {
                    if (minecraft.player == null || minecraft.gui == null || minecraft.gui.hud == null) {
                        return;
                    }
                    MutableComponent line = Component.literal("[Prism] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                            .append(Component.literal(pending.code() + ": ").withStyle(ChatFormatting.RED))
                            .append(Component.literal(pending.message()).withStyle(ChatFormatting.YELLOW));
                    if (!pending.source().isBlank()) {
                        line.append(Component.literal(" [" + pending.source() + "]").withStyle(ChatFormatting.GRAY));
                    }
                    minecraft.gui.hud.getChat().addClientSystemMessage(line);
                } catch (RuntimeException chatFailure) {
                    PrismMod.LOGGER.debug("Could not display Prism error in Minecraft chat", chatFailure);
                }
            });
            return true;
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Could not schedule Prism chat error", exception);
            return false;
        }
    }

    private static String compact(String value, int maxChars) {
        String compacted = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (compacted.length() <= maxChars) {
            return compacted;
        }
        return compacted.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private record PendingMessage(String code, String message, String source) {
    }
}
