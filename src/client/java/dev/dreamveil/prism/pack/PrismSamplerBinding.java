package dev.dreamveil.prism.pack;

record PrismSamplerBinding(String name, String resource, String filter, String wrap, String history) {
    PrismSamplerBinding {
        filter = filter == null || filter.isBlank() ? "linear" : filter;
        wrap = wrap == null || wrap.isBlank() ? "clamp" : wrap;
        history = history == null || history.isBlank() ? "current" : history;
    }

    boolean nearest() {
        return "nearest".equals(filter);
    }

    boolean previousHistory() {
        return "previous".equals(history);
    }

    boolean repeat() {
        return "repeat".equals(wrap);
    }

    String samplerKey() {
        return filter + ':' + wrap;
    }
}
