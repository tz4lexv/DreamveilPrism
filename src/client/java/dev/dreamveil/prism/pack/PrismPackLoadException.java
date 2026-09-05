package dev.dreamveil.prism.pack;

final class PrismPackLoadException extends Exception {
    private final String code;
    private final String source;

    PrismPackLoadException(String code, String message, String source) {
        super(message);
        this.code = code;
        this.source = source == null ? "" : source;
    }

    PrismPackLoadException(String code, String message, String source, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.source = source == null ? "" : source;
    }

    String code() {
        return code;
    }

    String source() {
        return source;
    }
}
