package com.devmate.knowledge.application;

import java.io.OutputStream;
import java.nio.file.Path;

/** Each locator receives at most one PUT, without SDK retries. PUT success requires a validated SHA-256 receipt. */
public interface ObjectStorage extends AutoCloseable {
    void put(ObjectLocation location, Path file, long length, String sha256, String putToken);
    Verification inspect(ObjectLocation location, long length, String sha256, String putToken);
    void delete(ObjectLocation location);
    void read(ObjectLocation location, long maximumBytes, OutputStream destination);
    @Override default void close() {}
}
