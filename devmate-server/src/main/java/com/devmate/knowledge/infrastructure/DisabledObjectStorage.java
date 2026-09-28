package com.devmate.knowledge.infrastructure;

import com.devmate.common.api.ErrorCode;
import com.devmate.knowledge.application.*;
import java.io.OutputStream;
import java.nio.file.Path;

public final class DisabledObjectStorage implements ObjectStorage {
    private StorageFailure disabled() { return new StorageFailure(ErrorCode.KNOWLEDGE_SERVICE_DISABLED, true); }
    @Override public void put(ObjectLocation location, Path file, long size, String sha, String token) { throw disabled(); }
    @Override public Verification inspect(ObjectLocation location, long size, String sha, String token) { throw disabled(); }
    @Override public void delete(ObjectLocation location) { throw disabled(); }
    @Override public void read(ObjectLocation location, long maximum, OutputStream destination) { throw disabled(); }
}
