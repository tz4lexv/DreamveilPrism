package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.PrismApiVersion;

/**
 * Content/environment identity for Prism pipeline-cache metadata.
 *
 * Minecraft 26.2's verified Blaze3D surface used by Prism does not expose a portable
 * native pipeline-cache blob import/export API, so this key invalidates Prism-owned
 * metadata/session reuse rather than pretending to serialize backend-native PSOs.
 */
final class PrismPipelineCacheIdentity {
    private static final String MINECRAFT_VERSION = "26.2";

    private PrismPipelineCacheIdentity() {
    }

    static long fingerprint(GpuDevice device, GpuFormat outputFormat) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, MINECRAFT_VERSION);
        hash = mix(hash, PrismMod.VERSION);
        hash = mix(hash, PrismApiVersion.CURRENT.toString());
        hash = mix(hash, System.getProperty("java.vendor", "unknown"));
        hash = mix(hash, System.getProperty("java.version", "unknown"));
        hash = mix(hash, System.getProperty("os.name", "unknown"));
        hash = mix(hash, System.getProperty("os.version", "unknown"));
        hash = mix(hash, System.getProperty("os.arch", "unknown"));
        hash = mix(hash, device.getClass().getName());
        hash = mix(hash, String.valueOf(device.getDeviceInfo()));
        hash = mix(hash, String.valueOf(outputFormat));
        return hash;
    }

    static void persistAndInvalidateMetadata(Path gameDir, GpuDevice device, GpuFormat outputFormat) {
        Path directory = gameDir.resolve("config").resolve("dreamveil-prism").resolve("pipeline-cache");
        Path identityFile = directory.resolve("identity.txt");
        String identity = Long.toUnsignedString(fingerprint(device, outputFormat), 16);
        try {
            Files.createDirectories(directory);
            boolean hadIdentity = Files.isRegularFile(identityFile);
            String previous = hadIdentity
                    ? Files.readString(identityFile, StandardCharsets.UTF_8).trim()
                    : "";
            if (hadIdentity && !previous.equals(identity)) {
                try (var stream = Files.list(directory)) {
                    for (Path path : stream.toList()) {
                        if (!path.equals(identityFile) && Files.isRegularFile(path)) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
            writeIdentityAtomically(identityFile, identity);
        } catch (IOException exception) {
            PrismMod.LOGGER.debug("Could not persist Prism pipeline-cache identity", exception);
        }
    }

    private static void writeIdentityAtomically(Path identityFile, String identity) throws IOException {
        Path temporary = identityFile.resolveSibling(identityFile.getFileName() + ".tmp");
        Files.writeString(temporary, identity + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, identityFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, identityFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long mix(long hash, String value) {
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
