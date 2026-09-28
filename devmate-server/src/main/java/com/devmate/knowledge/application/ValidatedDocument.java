package com.devmate.knowledge.application;

import java.nio.file.Path;

/** Private request-scoped data; not logged or exposed by a controller. */
public record ValidatedDocument(Path path, String filename, String fileType, long byteSize,
                                String sha256, String fingerprint) {
    @Override public String toString() { return "ValidatedDocument[private]"; }
}
