package com.devmate.knowledge.index;

import java.time.LocalDateTime;

/** Durable metadata and reservations also survive removal of the original document. */
public record IndexWork(long id, long owner, long project, long document, long processing, long generation,
        String sourceSha, String manifest, String spec, String state, boolean active, int chunks, long tokens,
        long bytes, int debts, boolean cleaned, long version, String lease, LocalDateTime leaseUntil, String error) {}
