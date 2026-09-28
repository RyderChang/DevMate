package com.devmate.knowledge.application;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;

/** Streaming strict UTF-8, bounded rolling code-point window, and fixed reproducible chunking. */
public final class TextChunker extends OutputStream {
    public static final String PARSER = "utf8-text-v1";
    public static final String STRATEGY = "text-window-v1";
    public static final long MAX_SOURCE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_TEXT_BYTES = 8L * 1024 * 1024;
    public static final int MAX_CHUNKS = 8192;
    private final long expectedBytes;
    private final String expectedSha;
    private final long deadline;
    private final LongSupplier ticker;
    private final MessageDigest original = digest();
    private final MessageDigest normalized = digest();
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    private final ByteBuffer input = ByteBuffer.allocate(8196);
    private final CharBuffer output = CharBuffer.allocate(8192);
    private final int[] points = new int[1201];
    private final int[] lines = new int[1201];
    private final List<TextChunk> chunks = new ArrayList<>();
    private int count, start, line = 1;
    private char high;
    private boolean first = true, afterCr, finished;
    private long sourceBytes, textBytes;
    private ProcessingFailure failure;

    public TextChunker(long expectedBytes, String expectedSha, long deadline, LongSupplier ticker) {
        this.expectedBytes = expectedBytes; this.expectedSha = expectedSha; this.deadline = deadline; this.ticker = ticker;
    }
    @Override public void write(int value) { write(new byte[]{(byte) value}, 0, 1); }
    @Override public void write(byte[] data, int offset, int length) {
        check();
        if (finished || sourceBytes + length > MAX_SOURCE_BYTES || sourceBytes + length > expectedBytes) fail("INTEGRITY_MISMATCH");
        original.update(data, offset, length); sourceBytes += length;
        while (length > 0) {
            int copied = Math.min(length, input.remaining());
            input.put(data, offset, copied); offset += copied; length -= copied;
            decode(false);
        }
    }
    private void decode(boolean end) {
        input.flip();
        do {
            output.clear();
            var result = decoder.decode(input, output, end);
            if (result.isError()) fail("INVALID_TEXT");
            output.flip();
            while (output.hasRemaining()) character(output.get());
            if (!result.isOverflow()) break;
        } while (true);
        input.compact(); check();
    }
    private void character(char value) {
        if (high != 0) {
            if (!Character.isLowSurrogate(value)) fail("INVALID_TEXT");
            point(Character.toCodePoint(high, value)); high = 0;
        } else if (Character.isHighSurrogate(value)) high = value;
        else { if (Character.isLowSurrogate(value)) fail("INVALID_TEXT"); point(value); }
    }
    private void point(int value) {
        if (first) { first = false; if (value == 0xfeff) return; }
        if (value == 0) fail("INVALID_TEXT");
        if (value == '\n' && afterCr) { afterCr = false; return; }
        afterCr = value == '\r';
        if (afterCr) value = '\n';
        // Encode one scalar directly: no full normalized text or per-character byte array.
        if (value < 0x80) normalized.update((byte) value);
        else if (value < 0x800) { normalized.update((byte) (0xc0 | value >> 6)); normalized.update((byte) (0x80 | value & 63)); }
        else if (value < 0x10000) {
            normalized.update((byte) (0xe0 | value >> 12)); normalized.update((byte) (0x80 | value >> 6 & 63)); normalized.update((byte) (0x80 | value & 63));
        } else {
            normalized.update((byte) (0xf0 | value >> 18)); normalized.update((byte) (0x80 | value >> 12 & 63));
            normalized.update((byte) (0x80 | value >> 6 & 63)); normalized.update((byte) (0x80 | value & 63));
        }
        points[count] = value; lines[count] = line; count++;
        if (value == '\n') line++;
        if (count > 1200) {
            int paragraph = 0, newline = 0;
            for (int boundary = 800; boundary <= 1000; boundary++) {
                if (points[boundary - 1] == '\n') {
                    newline = boundary;
                    if (points[boundary - 2] == '\n') paragraph = boundary;
                }
            }
            emit(paragraph != 0 ? paragraph : newline != 0 ? newline : 1000, false);
        }
    }
    private void emit(int end, boolean last) {
        check();
        String text = new String(points, 0, end);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (chunks.size() >= MAX_CHUNKS || textBytes + bytes.length > MAX_TEXT_BYTES) fail("CHUNK_LIMIT_EXCEEDED");
        chunks.add(new TextChunk(chunks.size(), start, start + end, lines[0], lines[end - 1], text, sha(bytes), bytes.length));
        textBytes += bytes.length;
        if (!last) {
            int advance = end - 100;
            System.arraycopy(points, advance, points, 0, count - advance);
            System.arraycopy(lines, advance, lines, 0, count - advance);
            count -= advance; start += advance;
        }
    }
    public ParsedDocument finish() {
        check();
        if (finished) throw new IllegalStateException("Parser already finished");
        decode(true);
        if (high != 0 || input.position() != 0) fail("INVALID_TEXT");
        if (sourceBytes != expectedBytes || !HexFormat.of().formatHex(original.digest()).equals(expectedSha)) fail("INTEGRITY_MISMATCH");
        if (count == 0) fail("INVALID_TEXT");
        emit(count, true); finished = true;
        return new ParsedDocument(HexFormat.of().formatHex(normalized.digest()), chunks, textBytes);
    }
    public ProcessingFailure failure() { return failure; }
    public void check() {
        if (failure != null) throw failure;
        if (Thread.currentThread().isInterrupted() || ticker.getAsLong() - deadline >= 0) fail("PROCESSING_TIMEOUT");
    }
    private void fail(String code) { failure = new ProcessingFailure(code); throw failure; }
    public static String sha(byte[] data) { return HexFormat.of().formatHex(digest().digest(data)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
