package dev.dreamveil.prism.api.resource;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable namespaced identifier used by Prism creator APIs. */
public record PrismResourceId(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    public PrismResourceId {
        namespace = Objects.requireNonNull(namespace, "namespace");
        path = Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid Prism resource namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid Prism resource path: " + path);
        }
    }

    public static PrismResourceId of(String namespace, String path) {
        return new PrismResourceId(namespace, path);
    }

    public static PrismResourceId parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Resource id must not be null");
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("Prism resource id must be namespace:path: " + value);
        }
        return of(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
