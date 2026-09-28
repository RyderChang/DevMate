package com.devmate.ai.embedding;

/** Safe code only. A transport failure does not establish remote termination. */
public class EmbeddingFailure extends RuntimeException {
    private final String code;
    private final boolean ended;
    public EmbeddingFailure(String code, boolean ended) { super(code); this.code = code; this.ended = ended; }
    public String code() { return code; }
    public boolean ended() { return ended; }
}
