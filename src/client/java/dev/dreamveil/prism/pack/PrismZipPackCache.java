package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safely expands user-facing ZIP packs into a private cache so the normal path-safe loader can operate on them.
 * Cache replacement is transactional: a partial/corrupt ZIP never destroys the last successfully materialized copy.
 */
final class PrismZipPackCache {
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final Path cacheRoot;

    PrismZipPackCache(Path gameDir) {
        this.cacheRoot = gameDir.resolve(".prism-cache").resolve("shaderpacks").toAbsolutePath().normalize();
    }

    Path materialize(Path archive) throws PrismPackLoadException {
        Path zip = archive.toAbsolutePath().normalize();
        String key = sha256(zip.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24)
                + "-" + sanitize(zip.getFileName().toString());
        Path destination = cacheRoot.resolve(key).normalize();
        Path stamp = destination.resolve(".prism-source");
        final String expected;
        try {
            long archiveBytes = Files.size(zip);
            if (archiveBytes > MAX_ARCHIVE_BYTES) {
                throw new PrismPackLoadException(
                        "archive_limit",
                        "Archive exceeds Prism's " + MAX_ARCHIVE_BYTES + " byte compressed-size limit",
                        zip.getFileName().toString());
            }
            expected = "sha256:" + sha256(zip);
            if (Files.isRegularFile(stamp)
                    && Files.readString(stamp, StandardCharsets.UTF_8).equals(expected)
                    && Files.isDirectory(destination)) {
                return destination;
            }
        } catch (IOException exception) {
            throw new PrismPackLoadException(
                    "archive_read",
                    "Could not inspect shader-pack archive: " + exception.getMessage(),
                    zip.getFileName().toString(),
                    exception);
        }

        Path temporary = cacheRoot.resolve(key + ".tmp-" + Long.toUnsignedString(System.nanoTime(), 36)).normalize();
        try {
            Files.createDirectories(cacheRoot);
            deleteTree(temporary);
            Files.createDirectories(temporary);
            extract(zip, temporary);
            Files.writeString(temporary.resolve(".prism-source"), expected, StandardCharsets.UTF_8);

            // Only after the new copy is completely extracted do we replace the old cache.
            // Keep the previous completed cache until the replacement move succeeds so an I/O failure cannot destroy it.
            Path backup = cacheRoot.resolve(key + ".bak-" + Long.toUnsignedString(System.nanoTime(), 36)).normalize();
            boolean backedUp = false;
            try {
                if (Files.exists(destination)) {
                    moveDirectory(destination, backup);
                    backedUp = true;
                }
                moveDirectory(temporary, destination);
                if (backedUp) {
                    deleteTree(backup);
                }
            } catch (IOException replacementFailure) {
                try {
                    deleteTree(destination);
                    if (backedUp && Files.exists(backup)) {
                        moveDirectory(backup, destination);
                    }
                } catch (IOException restoreFailure) {
                    replacementFailure.addSuppressed(restoreFailure);
                }
                throw replacementFailure;
            }
            return destination;
        } catch (PrismPackLoadException exception) {
            try { deleteTree(temporary); } catch (IOException ignored) { }
            throw exception;
        } catch (IOException exception) {
            try { deleteTree(temporary); } catch (IOException ignored) { }
            throw new PrismPackLoadException(
                    "archive_read",
                    "Could not prepare shader-pack archive: " + exception.getMessage(),
                    zip.getFileName().toString(),
                    exception);
        }
    }

    private static void extract(Path zip, Path destination) throws IOException, PrismPackLoadException {
        try (InputStream input = Files.newInputStream(zip); ZipInputStream stream = new ZipInputStream(input)) {
            ZipEntry entry;
            long total = 0L;
            int count = 0;
            Set<String> portableNames = new HashSet<>();
            while ((entry = stream.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new PrismPackLoadException("archive_limit", "Archive contains too many entries", zip.getFileName().toString());
                }
                String name = entry.getName().replace('\\', '/');
                if (!isPortableEntryName(name)) {
                    throw new PrismPackLoadException("archive_path_escape", "Unsafe archive entry: " + name, zip.getFileName().toString());
                }
                final Path target;
                try {
                    target = destination.resolve(name).normalize();
                } catch (InvalidPathException exception) {
                    throw new PrismPackLoadException(
                            "archive_path_escape",
                            "Invalid archive entry: " + name,
                            zip.getFileName().toString(),
                            exception);
                }
                if (!target.startsWith(destination)) {
                    throw new PrismPackLoadException("archive_path_escape", "Unsafe archive entry: " + name, zip.getFileName().toString());
                }
                String portableName = destination.relativize(target).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!portableNames.add(portableName)) {
                    throw new PrismPackLoadException(
                            "archive_duplicate_path",
                            "Archive contains duplicate or case-colliding entry: " + name,
                            zip.getFileName().toString());
                }
                if (entry.getSize() > MAX_FILE_BYTES) {
                    throw new PrismPackLoadException(
                            "archive_limit",
                            "Archive entry exceeds Prism extraction limits: " + name,
                            zip.getFileName().toString());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    long written = 0L;
                    byte[] buffer = new byte[8192];
                    try (var output = Files.newOutputStream(target)) {
                        int read;
                        while ((read = stream.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            written += read;
                            total += read;
                            if (written > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                                throw new PrismPackLoadException("archive_limit", "Archive exceeds Prism extraction limits", zip.getFileName().toString());
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
                stream.closeEntry();
            }
        }
    }

    private static boolean isPortableEntryName(String name) {
        if (name.isBlank() || name.startsWith("/") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            return false;
        }
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (trimmed.isBlank() || trimmed.equals(".prism-source")) {
            return false;
        }
        for (String segment : trimmed.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                return false;
            }
            for (int index = 0; index < segment.length(); index++) {
                if (Character.isISOControl(segment.charAt(index))) {
                    return false;
                }
            }
            int dot = segment.indexOf('.');
            String stem = (dot < 0 ? segment : segment.substring(0, dot)).toLowerCase(Locale.ROOT);
            if (WINDOWS_RESERVED_NAMES.contains(stem)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            var digest = newDigest();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(newDigest().digest(value));
    }

    private static java.security.MessageDigest newDigest() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }


    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String sanitize(String value) {
        String safe = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return safe.isBlank() ? "pack.zip" : safe;
    }
}
