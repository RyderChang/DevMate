package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.UploadTempFiles;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** The filter reserves before servlet multipart parsing; direct application calls use the same gate. */
@Component
public class UploadSlots {
    private final Semaphore permits;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Path> reservation = new ThreadLocal<>();
    private final UploadTempFiles files;
    private final long byteReservation;
    public UploadSlots(KnowledgeProperties properties, UploadTempFiles files) {
        permits = new Semaphore(properties.getMaxConcurrentUploads()); this.files = files;
        byteReservation = properties.getMaxRequestBytes() + properties.getMaxFileBytes();
    }
    public Slot acquire() {
        int current = depth.get();
        if (current == 0 && !permits.tryAcquire()) { depth.remove(); throw new BusinessException(ErrorCode.DOCUMENT_UPLOAD_LIMITED); }
        if (current == 0) {
            try { reservation.set(files.reserveBudget(byteReservation)); }
            catch (IOException error) { permits.release(); depth.remove(); throw new BusinessException(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE); }
        }
        depth.set(current + 1);
        return new Slot();
    }
    public final class Slot implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            if (closed) return;
            closed = true;
            int remaining = depth.get() - 1;
            if (remaining == 0) { files.release(reservation.get()); reservation.remove(); depth.remove(); permits.release(); } else depth.set(remaining);
        }
    }
}
