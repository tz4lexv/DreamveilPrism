package dev.dreamveil.prism.backend;

public interface PrismBackend extends AutoCloseable {
    PrismBackendInfo info();

    void initialize();

    @Override
    void close();
}
