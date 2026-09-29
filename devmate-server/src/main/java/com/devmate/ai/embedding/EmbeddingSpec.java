package com.devmate.ai.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class EmbeddingSpec {
    public static final String ID = "qwen3-0.6b-1024-cosine-v1";
    public static final String MODEL = "Qwen/Qwen3-Embedding-0.6B";
    public static final String REVISION = "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3";
    public static final String WEIGHT = "0437e45c94563b09e13cb7a64478fc406947a93cb34a7e05870fc8dcd48e23fd";
    public static final String TOKENIZER = "def76fb086971c7867b829c23a26261e38d9d74e02139253b38aeb9df8b4b50a";
    public static final String CONTRACT = ID + "|" + REVISION + "|" + WEIGHT + "|" + TOKENIZER
            + "|tokenizers=0.22.1|transformers=4.57.1|torch=2.8.0|safetensors=0.6.2|cpu-f32-sdpa-mask-4|last-nonpad-l2|nfc-eos151643|6000/4/6000/6000";
    public static final String FINGERPRINT = sha(CONTRACT);
    private EmbeddingSpec() {}
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public static void inputs(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.size() > 4 || texts.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 24000))
            throw new EmbeddingFailure("INVALID_INPUT", true);
    }
    public static void counts(List<Integer> counts, int inputs) {
        if (counts == null || counts.size() != inputs || counts.stream().anyMatch(t -> t == null || t < 1 || t > 6000)
                || counts.stream().mapToInt(Integer::intValue).sum() > 6000 || counts.stream().mapToInt(Integer::intValue).max().orElseThrow() * inputs > 6000)
            throw new EmbeddingFailure("TOKEN_LIMIT", true);
    }
    public static void vectors(List<float[]> vectors, int inputs) {
        if (vectors == null || vectors.size() != inputs) throw new EmbeddingFailure("INVALID_RESPONSE", true);
        for (float[] vector : vectors) {
            if (vector == null || vector.length != 1024) throw new EmbeddingFailure("INVALID_RESPONSE", true);
            double norm = 0;
            for (float value : vector) { if (!Float.isFinite(value)) throw new EmbeddingFailure("INVALID_RESPONSE", true); norm += (double) value * value; }
            if (Math.abs(Math.sqrt(norm) - 1) > 0.001) throw new EmbeddingFailure("INVALID_RESPONSE", true);
        }
    }
}
