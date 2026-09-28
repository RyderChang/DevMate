package com.devmate.knowledge.application;

/** Safe deterministic failure; no input or infrastructure diagnostics are retained. */
public final class ProcessingFailure extends RuntimeException {
    private final String code;
    public ProcessingFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
