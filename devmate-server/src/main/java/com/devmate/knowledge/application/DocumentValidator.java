package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.UploadTempFiles;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class DocumentValidator {
    private final UploadTempFiles temporary;
    private final KnowledgeProperties properties;
    public DocumentValidator(UploadTempFiles temporary, KnowledgeProperties properties) {
        this.temporary = temporary; this.properties = properties;
    }
    public ValidatedDocument prepare(MultipartFile file) {
        if (file == null) throw invalid();
        String filename = normalizeName(file.getOriginalFilename());
        int dot = filename.lastIndexOf('.');
        String type = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!type.equals("txt") && !type.equals("md")) throw new BusinessException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED);
        if (file.getSize() > properties.getMaxFileBytes()) throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
        Path path = null;
        try {
            path = temporary.create();
            MessageDigest hash = digest(); long length = 0;
            try (var input = file.getInputStream(); var output = Files.newOutputStream(path)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    length += count;
                    if (length > properties.getMaxFileBytes()) throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
                    for (int i = 0; i < count; i++) if (buffer[i] == 0) throw invalid();
                    hash.update(buffer, 0, count); output.write(buffer, 0, count);
                }
            }
            if (length == 0) throw invalid();
            boolean content = false; boolean first = true;
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
                char[] chars = new char[4096]; int count;
                while ((count = reader.read(chars)) != -1) {
                    for (int i = 0; i < count; i++) {
                        char value = chars[i];
                        if (first && value == '\uFEFF') { first = false; continue; }
                        first = false;
                        if (!Character.isWhitespace(value) && !Character.isSpaceChar(value)) content = true;
                    }
                }
            }
            if (!content) throw invalid();
            String sha = HexFormat.of().formatHex(hash.digest());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var fingerprint = new DataOutputStream(bytes)) {
                byte[] nameBytes = filename.getBytes(StandardCharsets.UTF_8);
                fingerprint.writeInt(nameBytes.length); fingerprint.write(nameBytes);
                fingerprint.writeUTF(type); fingerprint.writeLong(length); fingerprint.writeUTF(sha);
            }
            return new ValidatedDocument(path, filename, type, length, sha, HexFormat.of().formatHex(digest().digest(bytes.toByteArray())));
        } catch (IOException error) {
            if (path != null) temporary.release(path);
            throw invalid();
        } catch (RuntimeException error) {
            if (path != null) temporary.release(path);
            throw error;
        }
    }
    public void release(ValidatedDocument input) { temporary.release(input.path()); }
    public String normalizeName(String name) {
        if (name == null) throw invalid();
        String normalized = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")
                || normalized.codePointCount(0, normalized.length()) > 200
                || normalized.codePoints().anyMatch(value -> value == '/' || value == '\\' || Character.isISOControl(value) || Character.getType(value) == Character.FORMAT
                        || (value >= 0xD800 && value <= 0xDFFF))) throw invalid();
        return normalized;
    }
    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_PARAMETER); }
}
