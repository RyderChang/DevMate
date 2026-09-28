package com.devmate.knowledge;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.application.DocumentValidator;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.UploadTempFiles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentValidatorTest {
    @TempDir Path directory;

    @Test
    void reportsTemporaryFileCreationFailureAsStorageUnavailableWithoutDiagnostics() throws Exception {
        var files = mock(UploadTempFiles.class);
        when(files.create()).thenThrow(new IOException("synthetic private filesystem diagnostic"));
        var validator = new DocumentValidator(files, properties());
        assertThatThrownBy(() -> validator.prepare(file("note.txt", "x")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE.getCode());
                    assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE.getMessage());
                    assertThat(error.getCause()).isNull();
                });
        verify(files, never()).release(any());
    }

    @Test
    void reportsUploadCopyFailureAsStorageUnavailableAndRemovesTemporaryFile() throws Exception {
        var properties = properties();
        try (var files = new UploadTempFiles(properties, Clock.systemUTC())) {
            var validator = new DocumentValidator(files, properties);
            var broken = new MockMultipartFile("file", "note.txt", "text/plain", new byte[]{'x'}) {
                @Override public InputStream getInputStream() throws IOException {
                    throw new IOException("synthetic private stream diagnostic");
                }
            };
            assertThatThrownBy(() -> validator.prepare(broken))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getCode()).isEqualTo(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE.getCode());
                        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(error.getMessage()).isEqualTo(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE.getMessage());
                    });
            try (var paths = Files.list(files.multipartDirectory().getParent())) {
                assertThat(paths.noneMatch(path -> path.toString().endsWith(".bin"))).isTrue();
            }
        }
    }

    @Test
    void reportsValidationReadFailureAsStorageUnavailableAndReleasesItsCopy() throws Exception {
        Path copy = directory.resolve("validated-copy.bin");
        var files = mock(UploadTempFiles.class);
        when(files.create()).thenReturn(copy);
        var validator = new DocumentValidator(files, properties());
        var disappears = new MockMultipartFile("file", "note.txt", "text/plain", new byte[]{'x'}) {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[]{'x'}) {
                    @Override public void close() throws IOException {
                        super.close();
                        Files.delete(copy);
                    }
                };
            }
        };
        assertThatThrownBy(() -> validator.prepare(disappears))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE.getCode());
                    assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE.getMessage());
                    assertThat(error.getCause()).isNull();
                });
        verify(files).release(copy);
        assertThat(copy).doesNotExist();
    }

    @Test
    void validatesBomAndPreservesOriginalBytesAndTheirSha256() throws Exception {
        byte[] input = "\uFEFF# Synthetic\n正文".getBytes(StandardCharsets.UTF_8);
        var properties = properties();
        try (var files = new UploadTempFiles(properties, Clock.systemUTC())) {
            var validator = new DocumentValidator(files, properties);
            var result = validator.prepare(new MockMultipartFile("file", "  Cafe\u0301.MD  ", "application/octet-stream", input));
            assertThat(result.filename()).isEqualTo("Café.MD");
            assertThat(result.fileType()).isEqualTo("md");
            assertThat(Files.readAllBytes(result.path())).containsExactly(input);
            assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)));
            assertThat(result.byteSize()).isEqualTo(input.length);
            validator.release(result);
            assertThat(Files.exists(result.path())).isFalse();
        }
    }

    @Test
    void acceptsExactly5MibAndRejectsOneAdditionalByteEvenWithALyingLength() throws Exception {
        var properties = properties();
        byte[] valid = new byte[5 * 1024 * 1024]; Arrays.fill(valid, (byte) 'x');
        try (var files = new UploadTempFiles(properties, Clock.systemUTC())) {
            var validator = new DocumentValidator(files, properties);
            var result = validator.prepare(new MockMultipartFile("file", "boundary.txt", "text/plain", valid));
            assertThat(result.byteSize()).isEqualTo(valid.length); validator.release(result);
            byte[] extra = Arrays.copyOf(valid, valid.length + 1); extra[extra.length - 1] = 'x';
            var lying = new MockMultipartFile("file", "boundary.txt", "text/plain", extra) {
                @Override public long getSize() { return 1; }
            };
            assertThatThrownBy(() -> validator.prepare(lying)).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getCode()).isEqualTo(413));
            try (var paths = Files.list(files.multipartDirectory().getParent())) {
                assertThat(paths.noneMatch(path -> path.toString().endsWith(".bin"))).isTrue();
            }
        }
    }

    @Test
    void rejectsEmptyWhitespaceNulAndMalformedUtf8AndRemovesTemporaryFiles() throws Exception {
        var properties = properties();
        try (var files = new UploadTempFiles(properties, Clock.systemUTC())) {
            var validator = new DocumentValidator(files, properties);
            for (byte[] input : new byte[][]{new byte[0], "\uFEFF \t\r\n\u3000\u00A0".getBytes(StandardCharsets.UTF_8),
                    new byte[]{'x',0}, new byte[]{(byte)0xC0,(byte)0xAF}, new byte[]{(byte)0xED,(byte)0xA0,(byte)0x80}, new byte[]{(byte)0xE2,(byte)0x82}}) {
                assertThatThrownBy(() -> validator.prepare(new MockMultipartFile("file", "note.txt", "text/plain", input)))
                        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
            }
            try (var paths = Files.list(files.multipartDirectory().getParent())) {
                assertThat(paths.noneMatch(path -> path.toString().endsWith(".bin"))).isTrue();
            }
        }
    }

    @Test
    void unicodeFilenameLimitCountsCodePointsAndRejectsPathsControlsAndUnsupportedExtensions() throws Exception {
        var properties = properties();
        try (var files = new UploadTempFiles(properties, Clock.systemUTC())) {
            var validator = new DocumentValidator(files, properties);
            assertThat(validator.normalizeName("😀".repeat(196) + ".txt").codePointCount(0, 396)).isEqualTo(200);
            for (String name : new String[]{"😀".repeat(197) + ".txt", ".", "..", "../note.txt", "C:\\note.txt", "note\n.txt", "note\u0000.txt", "\uD800.txt"})
                assertThatThrownBy(() -> validator.normalizeName(name)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> validator.prepare(new MockMultipartFile("file", "note.pdf", "text/plain", "x".getBytes())))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(415));
        }
    }

    @Test
    void fingerprintsDistinguishNamesAndContentButShareCanonicalUnicodeNames() throws Exception {
        var properties = properties();
        try (var files = new UploadTempFiles(properties, Clock.systemUTC())) {
            var validator = new DocumentValidator(files, properties);
            var first = validator.prepare(file("Cafe\u0301.txt", "x"));
            var canonical = validator.prepare(file("Café.txt", "x"));
            var renamed = validator.prepare(file("Other.txt", "x"));
            var changed = validator.prepare(file("Café.txt", "y"));
            assertThat(first.fingerprint()).isEqualTo(canonical.fingerprint()).isNotEqualTo(renamed.fingerprint()).isNotEqualTo(changed.fingerprint());
            for (var value : java.util.List.of(first, canonical, renamed, changed)) validator.release(value);
        }
    }
    private MockMultipartFile file(String filename, String text) { return new MockMultipartFile("file", filename, "text/plain", text.getBytes(StandardCharsets.UTF_8)); }
    private KnowledgeProperties properties() { var result = new KnowledgeProperties(); result.setTempDirectory(directory.toString()); return result; }
}
