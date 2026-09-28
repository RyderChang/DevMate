package com.devmate.knowledge;

import com.devmate.knowledge.application.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TextChunkerTest {
    @Test void normalizesOnlyOneLeadingBomAndLineEndingsWithoutChangingLiteralContent() {
        String source = "\ufeff\ufeff  A\r\n\rB\n```java\r\n<x>\r```\n[malicious](https://example.invalid/include) e\u0301 😀  ";
        String normalized = "\ufeff  A\n\nB\n```java\n<x>\n```\n[malicious](https://example.invalid/include) e\u0301 😀  ";
        var result = parse(source, 1);
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().getFirst().text()).isEqualTo(normalized);
        assertThat(result.normalizedSha256()).isEqualTo(TextChunker.sha(normalized.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.normalizedSha256()).isNotEqualTo(TextChunker.sha(source.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.chunks().getFirst().endLine()).isEqualTo(7);
    }
    @Test void streamingDecodeHandlesEveryUtf8AndCrLfBoundaryReproducibly() {
        String source = "\ufeff" + "😀中\r\na\re\u0301\n\n".repeat(1000);
        var expected = parse(source, 8192);
        for (int step : List.of(1,2,3,7,8191,8196,100000)) assertThat(parse(source, step)).isEqualTo(expected);
    }
    @Test void rejectsMalformedTruncatedUtf8AndNulRatherThanReplacingIt() {
        for (byte[] bytes : List.of(new byte[]{(byte) 0xc3,0x28}, new byte[]{(byte) 0xf0,(byte) 0x9f},
                new byte[]{'x',0}, new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80})) {
            var parser = parser(bytes);
            assertThatThrownBy(() -> { for (byte value : bytes) parser.write(value); parser.finish(); })
                    .isInstanceOfSatisfying(ProcessingFailure.class, failure -> assertThat(failure.code()).isEqualTo("INVALID_TEXT"));
        }
    }
    @Test void revalidatesExactLengthAndOriginalShaBeforeReturningAnyGeneration() {
        byte[] bytes = "synthetic".getBytes(StandardCharsets.UTF_8);
        var shortRead = new TextChunker(bytes.length+1, TextChunker.sha(bytes), Long.MAX_VALUE, () -> 0);
        shortRead.write(bytes,0,bytes.length);
        assertFailure(shortRead::finish, "INTEGRITY_MISMATCH");
        var wrongHash = new TextChunker(bytes.length,"a".repeat(64),Long.MAX_VALUE,() -> 0);
        wrongHash.write(bytes,0,bytes.length); assertFailure(wrongHash::finish,"INTEGRITY_MISMATCH");
        var oversized = new TextChunker(1,TextChunker.sha(bytes),Long.MAX_VALUE,() -> 0);
        assertFailure(() -> oversized.write(bytes,0,bytes.length),"INTEGRITY_MISMATCH");
    }
    @Test void exactBoundariesAndFinalChunkRespectTheConfirmedWindowRule() {
        for (int length : List.of(799,800,999,1000,1199,1200)) assertThat(parse("x".repeat(length),17).chunks()).hasSize(1);
        var chunks = parse("x".repeat(1201),17).chunks();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).start()).isZero(); assertThat(chunks.get(0).end()).isEqualTo(1000);
        assertThat(chunks.get(1).start()).isEqualTo(900); assertThat(chunks.get(1).end()).isEqualTo(1201);
        assertThat(parse("x".repeat(2100),11).chunks()).hasSize(2);
    }
    @Test void choosesLastParagraphBeforeNewlineAndIncludesSeparatorsInPreviousChunk() {
        String source = "a".repeat(798) + "\n\n" + "b".repeat(99) + "\n" + "c".repeat(399);
        var chunks = parse(source,3).chunks();
        assertThat(chunks.getFirst().end()).isEqualTo(800);
        assertThat(chunks.getFirst().text()).endsWith("\n\n");
        source = "a".repeat(948) + "\n\n" + "b".repeat(49) + "\n" + "c".repeat(301);
        assertThat(parse(source,7).chunks().getFirst().end()).isEqualTo(950);
        source = "a".repeat(799) + "\n" + "b".repeat(199) + "\n" + "c".repeat(302);
        assertThat(parse(source,7).chunks().getFirst().end()).isEqualTo(1000);
    }
    @Test void supplementaryCodePointsLineNumbersHashCoverageAndOverlapMatchNormalizedView() {
        String normalized = "😀中e\u0301\n\n ".repeat(2000);
        verify(normalized, parse(normalized,2));
        verify("  \n\nno final newline  ", parse("  \r\n\rno final newline  ",1));
        verify("\n",parse("\r\n",1));
    }
    @Test void repeatContentAndUnclosedMarkdownFencesAreKeptAsSeparatePositionedChunks() {
        String source = "```\n" + "x".repeat(10000);
        verify(source,parse(source,1));
        var repeat = parse("x".repeat(10000),17).chunks();
        assertThat(repeat.get(0).sha256()).isEqualTo(repeat.get(1).sha256());
        assertThat(repeat.get(0).start()).isNotEqualTo(repeat.get(1).start());
    }
    @Test void randomizedUnicodeDocumentsHaveCompleteCoverageAndStableManifests() {
        Random random = new Random(17);
        int[] alphabet = {'x',' ','\n',0x4e2d,0x1f600,0x301};
        for (int iteration=0; iteration<35; iteration++) {
            StringBuilder text = new StringBuilder();
            for (int i=0; i<1201+random.nextInt(8000); i++) text.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
            String source = text.toString(); var parsed = parse(source,11);
            verify(source,parsed); assertThat(parse(source,8196)).isEqualTo(parsed);
        }
    }
    @Test void maximumSourceIsBoundedAndExtremeSingleLineDoesNotCreateOversizedChunks() {
        String source = "x".repeat((int)TextChunker.MAX_SOURCE_BYTES);
        var result = parse(source,8192);
        assertThat(result.chunks()).hasSize(5826);
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.end()-chunk.start()).isLessThanOrEqualTo(1200));
        assertThat(result.textBytes()).isLessThanOrEqualTo(TextChunker.MAX_TEXT_BYTES);
        byte[] excess = new byte[(int)TextChunker.MAX_SOURCE_BYTES+1];
        assertFailure(() -> parser(excess).write(excess,0,excess.length),"INTEGRITY_MISMATCH");
    }
    @Test void monotonicDeadlineIsCheckedDuringReadAndBeforeFinish() {
        AtomicLong time = new AtomicLong(); byte[] bytes = "x".getBytes();
        var parser = new TextChunker(1,TextChunker.sha(bytes),30,time::get);
        parser.write(bytes,0,1); time.set(30); assertFailure(parser::finish,"PROCESSING_TIMEOUT");
        var expired = new TextChunker(1,TextChunker.sha(bytes),30,time::get);
        assertFailure(() -> expired.write(bytes,0,1),"PROCESSING_TIMEOUT");
    }
    private ParsedDocument parse(String source, int step) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8); var parser = parser(bytes);
        for (int offset=0; offset<bytes.length; offset+=step) parser.write(bytes,offset,Math.min(step,bytes.length-offset));
        return parser.finish();
    }
    private TextChunker parser(byte[] bytes) { return new TextChunker(bytes.length,TextChunker.sha(bytes),Long.MAX_VALUE,() -> 0); }
    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ProcessingFailure.class,error -> assertThat(error.code()).isEqualTo(code));
    }
    private void verify(String normalized, ParsedDocument result) {
        int[] points = normalized.codePoints().toArray(); boolean[] covered = new boolean[points.length]; int lastEnd=0, ordinal=0;
        int[] lines = new int[points.length]; int line=1;
        for (int i=0;i<points.length;i++) { lines[i]=line; if (points[i]=='\n') line++; }
        for (TextChunk chunk : result.chunks()) {
            assertThat(chunk.ordinal()).isEqualTo(ordinal++);
            assertThat(chunk.start()).isEqualTo(ordinal==1 ? 0 : lastEnd-100);
            assertThat(chunk.text()).isEqualTo(new String(points,chunk.start(),chunk.end()-chunk.start()));
            assertThat(chunk.startLine()).isEqualTo(lines[chunk.start()]); assertThat(chunk.endLine()).isEqualTo(lines[chunk.end()-1]);
            byte[] bytes=chunk.text().getBytes(StandardCharsets.UTF_8);
            assertThat(chunk.byteSize()).isEqualTo(bytes.length); assertThat(chunk.sha256()).isEqualTo(TextChunker.sha(bytes));
            assertThat(chunk.end()-chunk.start()).isLessThanOrEqualTo(1200);
            if (chunk.end()!=points.length) assertThat(chunk.end()-chunk.start()).isBetween(800,1000);
            for (int i=chunk.start();i<chunk.end();i++) covered[i]=true;
            lastEnd=chunk.end();
        }
        assertThat(lastEnd).isEqualTo(points.length); assertThat(covered).containsOnly(true);
        assertThat(result.textBytes()).isEqualTo(result.chunks().stream().mapToLong(TextChunk::byteSize).sum());
        assertThat(result.normalizedSha256()).isEqualTo(TextChunker.sha(normalized.getBytes(StandardCharsets.UTF_8)));
    }
}
