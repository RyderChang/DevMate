package com.devmate.conversation.service;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class RagInput {
    private RagInput() {}
    public static String content(String value) {
        if (value == null) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        String result = value.strip();
        if (result.isEmpty() || result.length() > 8000 || !unicode(result))
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        return result;
    }
    public static String uuid(String value) {
        try {
            String id = UUID.fromString(value).toString();
            if (!id.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException error) { throw new BusinessException(ErrorCode.INVALID_PARAMETER); }
    }
    public static boolean unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isSurrogate(c) && (!Character.isHighSurrogate(c)
                    || ++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))) return false;
        }
        return true;
    }
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
