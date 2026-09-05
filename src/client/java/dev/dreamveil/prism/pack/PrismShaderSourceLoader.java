package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Path-safe UTF-8 shader source loader with pack-local and versioned Prism include expansion.
 *
 * A Session is scoped to one candidate pack preparation. Raw/expanded sources are shared by
 * worker tasks, while hard limits bound memory/CPU consumption. Cancellation is cooperative so
 * obsolete hot-reload generations stop doing include IO/expansion instead of blocking newer work.
 */
final class PrismShaderSourceLoader {
    private static final int MAX_INCLUDE_DEPTH = 32;
    private static final int MAX_UNIQUE_SOURCES = 512;
    private static final long MAX_SOURCE_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_SESSION_SOURCE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_EXPANDED_CHARS = 16L * 1024L * 1024L;
    private static final Pattern INCLUDE = Pattern.compile(
            "^\\s*#include\\s+(?:\"([^\"]+)\"|<([^>]+)>)\\s*$");

    private PrismShaderSourceLoader() {
    }

    static String load(Path packRoot, String relativePath) throws PrismPackLoadException {
        return session(packRoot).load(relativePath);
    }

    static Session session(Path packRoot) {
        return new Session(packRoot, () -> false);
    }

    static Session session(Path packRoot, BooleanSupplier cancelled) {
        return new Session(packRoot, cancelled);
    }

    static final class Session {
        private final Path root;
        private final BooleanSupplier cancelled;
        private final ConcurrentHashMap<Path, String> expandedCache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Path, String> rawCache = new ConcurrentHashMap<>();
        private long sourceBytesRead;

        private Session(Path packRoot, BooleanSupplier cancelled) {
            this.root = packRoot.toAbsolutePath().normalize();
            this.cancelled = java.util.Objects.requireNonNull(cancelled, "cancelled");
        }

        String load(String relativePath) throws PrismPackLoadException {
            checkCancelled();
            Path source = resolve(root, root, relativePath, relativePath);
            return expand(source, new ArrayDeque<>(), 0);
        }

        int cachedSourceCount() {
            return rawCache.size();
        }

        synchronized long sourceBytesRead() {
            return sourceBytesRead;
        }

        private String expand(Path source, Deque<Path> stack, int depth) throws PrismPackLoadException {
            checkCancelled();
            if (depth > MAX_INCLUDE_DEPTH) {
                throw new PrismPackLoadException(
                        "include_depth",
                        "Shader include depth exceeded " + MAX_INCLUDE_DEPTH,
                        display(root, source));
            }
            if (stack.contains(source)) {
                StringBuilder cycle = new StringBuilder();
                for (Path path : stack) {
                    if (!cycle.isEmpty()) cycle.append(" -> ");
                    cycle.append(display(root, path));
                }
                cycle.append(" -> ").append(display(root, source));
                throw new PrismPackLoadException(
                        "include_cycle",
                        "Shader include cycle: " + cycle,
                        display(root, source));
            }

            String cached = expandedCache.get(source);
            if (cached != null) {
                return cached;
            }

            String text = readUtf8(source);
            checkCancelled();
            stack.addLast(source);
            try {
                StringBuilder output = new StringBuilder(Math.min(text.length() + 256, 1 << 20));
                String[] lines = text.split("\\R", -1);
                int sourceLine = 1;
                for (String line : lines) {
                    checkCancelled();
                    Matcher matcher = INCLUDE.matcher(line);
                    if (!matcher.matches()) {
                        output.append(line).append('\n');
                    } else {
                        String localInclude = matcher.group(1);
                        String builtInInclude = matcher.group(2);
                        String includeLabel;
                        String includeSource;
                        if (localInclude != null) {
                            Path include = resolve(root, source.getParent(), localInclude, display(root, source));
                            includeLabel = display(root, include);
                            includeSource = expand(include, stack, depth + 1);
                        } else {
                            includeLabel = "<" + builtInInclude + ">";
                            includeSource = PrismBuiltinShaderIncludes.require(
                                    builtInInclude, display(root, source) + ":" + sourceLine);
                        }
                        output.append("// Prism include begin: ").append(includeLabel).append('\n');
                        output.append(includeSource);
                        if (!includeSource.endsWith("\n")) output.append('\n');
                        output.append("// Prism include end: ").append(includeLabel).append('\n');
                        // Keep source-line context useful even when the backend compiler does not expose
                        // GLSL source-string mapping for #line diagnostics.
                        output.append("// Prism resume: ").append(display(root, source))
                                .append(':').append(sourceLine + 1).append('\n');
                    }
                    if (output.length() > MAX_EXPANDED_CHARS) {
                        throw new PrismPackLoadException(
                                "shader_expanded_limit",
                                "Expanded shader source exceeds " + MAX_EXPANDED_CHARS + " characters",
                                display(root, source));
                    }
                    sourceLine++;
                }
                checkCancelled();
                String expanded = output.toString();
                String previous = expandedCache.putIfAbsent(source, expanded);
                return previous == null ? expanded : previous;
            } finally {
                stack.removeLast();
            }
        }

        private String readUtf8(Path source) throws PrismPackLoadException {
            checkCancelled();
            String cached = rawCache.get(source);
            if (cached != null) {
                return cached;
            }

            final byte[] bytes;
            try {
                long size = Files.size(source);
                if (size > MAX_SOURCE_BYTES) {
                    throw new PrismPackLoadException(
                            "shader_source_limit",
                            "Shader source exceeds " + MAX_SOURCE_BYTES + " bytes",
                            display(root, source));
                }
                checkCancelled();
                bytes = Files.readAllBytes(source);
                if (bytes.length > MAX_SOURCE_BYTES) {
                    throw new PrismPackLoadException(
                            "shader_source_limit",
                            "Shader source exceeds " + MAX_SOURCE_BYTES + " bytes",
                            display(root, source));
                }
            } catch (PrismPackLoadException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new PrismPackLoadException(
                        "shader_read",
                        "Could not read shader source: " + exception.getMessage(),
                        display(root, source),
                        exception);
            }

            checkCancelled();
            final String decoded;
            try {
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException exception) {
                throw new PrismPackLoadException(
                        "shader_encoding",
                        "Shader sources must be valid UTF-8",
                        display(root, source),
                        exception);
            }
            checkCancelled();

            String text = decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
            synchronized (this) {
                String previous = rawCache.get(source);
                if (previous != null) {
                    return previous;
                }
                if (rawCache.size() >= MAX_UNIQUE_SOURCES) {
                    throw new PrismPackLoadException(
                            "shader_source_limit",
                            "Pack references more than " + MAX_UNIQUE_SOURCES + " unique shader/include files",
                            display(root, source));
                }
                if (sourceBytesRead + bytes.length > MAX_SESSION_SOURCE_BYTES) {
                    throw new PrismPackLoadException(
                            "shader_source_limit",
                            "Shader pack source set exceeds " + MAX_SESSION_SOURCE_BYTES + " bytes",
                            display(root, source));
                }
                rawCache.put(source, text);
                sourceBytesRead += bytes.length;
            }
            return text;
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Prism shader preprocessing cancelled");
            }
        }
    }

    private static Path resolve(Path root, Path base, String relative, String source)
            throws PrismPackLoadException {
        if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            throw new PrismPackLoadException(
                    "include_path",
                    "Shader paths must be non-empty portable relative paths using forward slashes",
                    source);
        }
        final Path resolved;
        try {
            resolved = base.resolve(relative).normalize().toAbsolutePath();
        } catch (java.nio.file.InvalidPathException exception) {
            throw new PrismPackLoadException("include_path", "Invalid shader path: " + relative, source, exception);
        }
        return PrismPackPathPolicy.requireContainedRegularFile(
                root,
                resolved,
                "include_path_escape",
                "include_missing",
                "Shader include '" + relative + "'",
                source);
    }

    private static String display(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
