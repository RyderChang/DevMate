package com.devmate.knowledge.infrastructure;

import com.devmate.knowledge.config.KnowledgeProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Each instance owns a locked, private directory. Sweeps never recurse or follow symlinks. */
public final class UploadTempFiles implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(UploadTempFiles.class);
    private static final String MARKER = "DevMate-knowledge-v1";
    private final Path root;
    private final Path directory;
    private final Path multipartDirectory;
    private final FileChannel channel;
    private final FileLock lock;
    private final Clock clock;
    private final long maximumBytes;
    private static final Object QUOTA_MONITOR = new Object();
    private final Set<Path> active = ConcurrentHashMap.newKeySet();

    public UploadTempFiles(KnowledgeProperties properties, Clock clock) throws IOException {
        this.clock = clock;
        maximumBytes = properties.getTempMaxBytes();
        root = Path.of(properties.getTempDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid upload directory");
        directory = Files.createDirectory(root.resolve("devmate-upload-" + UUID.randomUUID()));
        secure(directory);
        multipartDirectory = Files.createDirectory(directory.resolve("multipart"));
        secure(multipartDirectory);
        Path marker = directory.resolve("owner.lock");
        Files.writeString(marker, MARKER, StandardOpenOption.CREATE_NEW);
        secure(marker);
        channel = FileChannel.open(marker, StandardOpenOption.WRITE);
        lock = channel.lock();
    }

    public Path create() throws IOException {
        Path file = directory.resolve("upload-" + UUID.randomUUID() + ".bin");
        Files.createFile(file); secure(file); active.add(file);
        return file;
    }
    public Path multipartDirectory() { return multipartDirectory; }

    /** Durable worst-case reservation covers servlet spooling plus the validated copy. */
    public Path reserveBudget(long bytes) throws IOException {
        synchronized (QUOTA_MONITOR) {
            Path quota = root.resolve("quota.lock");
            if (Files.isSymbolicLink(quota)) throw new IOException("Invalid temporary quota lock");
            try (var channel = FileChannel.open(quota, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var acquired = channel.lock()) {
                secure(quota);
                long used = 0; int directories = 0; int entries = 0;
                try (var parents = Files.newDirectoryStream(root, "devmate-upload-*")) {
                    for (Path parent : parents) {
                        if (++directories > 50 || Files.isSymbolicLink(parent)) throw new IOException("Temporary directory review required");
                        if (!parent.getFileName().toString().matches("devmate-upload-[0-9a-f-]{36}") || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) continue;
                        for (Path folder : java.util.List.of(parent, parent.resolve("multipart"))) {
                            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) continue;
                            try (var files = Files.newDirectoryStream(folder)) {
                                for (Path file : files) {
                                    if (++entries > 500) throw new IOException("Temporary directory review required");
                                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                                    used = Math.addExact(used, Files.size(file));
                                    if (file.getFileName().toString().matches("reservation-[0-9a-f-]{36}\\.bin")) {
                                        if (Files.size(file) != Long.BYTES) throw new IOException("Invalid temporary reservation");
                                        long reserved = java.nio.ByteBuffer.wrap(Files.readAllBytes(file)).getLong();
                                        if (reserved < 1 || reserved > maximumBytes) throw new IOException("Invalid temporary reservation");
                                        used = Math.addExact(used, reserved);
                                    }
                                }
                            }
                        }
                    }
                }
                if (bytes < 1 || used > maximumBytes - bytes - Long.BYTES) throw new IOException("Temporary upload capacity unavailable");
                Path reservation = directory.resolve("reservation-" + UUID.randomUUID() + ".bin");
                Files.write(reservation, java.nio.ByteBuffer.allocate(Long.BYTES).putLong(bytes).array(), StandardOpenOption.CREATE_NEW);
                secure(reservation); active.add(reservation); return reservation;
            }
        }
    }
    public void release(Path file) {
        if (!active.remove(file)) return;
        try { Files.deleteIfExists(file); }
        catch (IOException error) { LOG.warn("Temporary document cleanup deferred traceId={}", org.slf4j.MDC.get("traceId")); }
    }

    public void sweep(int maximum) {
        int remaining = maximum;
        int inspected = 0;
        try (var directories = Files.newDirectoryStream(root, "devmate-upload-*")) {
            for (Path candidate : directories) {
                if (remaining <= 0 || ++inspected > 50) break;
                if (!candidate.getFileName().toString().matches("devmate-upload-[0-9a-f-]{36}")
                        || Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || !Files.getOwner(candidate).equals(Files.getOwner(directory))) continue;
                if (candidate.equals(directory)) { remaining -= cleanFiles(candidate, remaining, false); continue; }
                Path marker = candidate.resolve("owner.lock");
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 100
                        || !Files.readString(marker).equals(MARKER) || !old(marker)) continue;
                try (var other = FileChannel.open(marker, StandardOpenOption.WRITE)) {
                    try (var acquired = other.tryLock()) {
                        if (acquired == null) continue;
                        remaining -= cleanFiles(candidate, remaining, true);
                    }
                } catch (OverlappingFileLockException ignored) { continue; }
                // Unknown files prevent directory deletion. They are never removed by this sweep.
                Path multipart = candidate.resolve("multipart");
                if (!Files.isSymbolicLink(multipart)) removeEmpty(multipart);
                try (var entries = Files.newDirectoryStream(candidate)) {
                    boolean onlyMarker = true;
                    for (Path entry : entries) if (!entry.equals(marker)) onlyMarker = false;
                    if (!onlyMarker) continue;
                }
                Files.deleteIfExists(marker); removeEmpty(candidate);
            }
        } catch (IOException error) { LOG.warn("Temporary document sweep deferred traceId={}", org.slf4j.MDC.get("traceId")); }
    }

    private int cleanFiles(Path candidate, int maximum, boolean orphan) throws IOException {
        int removed = cleanDirectory(candidate, maximum, orphan, "(upload|reservation)-[0-9a-f-]{36}\\.bin");
        Path multipart = candidate.resolve("multipart");
        if (orphan && !Files.isSymbolicLink(multipart) && Files.isDirectory(multipart, LinkOption.NOFOLLOW_LINKS))
            removed += cleanDirectory(multipart, maximum - removed, true, "upload_[a-zA-Z0-9_-]+\\.tmp");
        return removed;
    }
    private int cleanDirectory(Path parent, int maximum, boolean orphan, String pattern) throws IOException {
        int removed = 0;
        int inspected = 0;
        try (var files = Files.newDirectoryStream(parent)) {
            for (Path file : files) {
                if (removed >= maximum || ++inspected > 500) break;
                if (file.getFileName().toString().matches(pattern) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && !active.contains(file) && (orphan || old(file))) {
                    Files.delete(file); removed++;
                }
            }
        }
        return removed;
    }
    private boolean old(Path file) throws IOException {
        return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().plus(Duration.ofMinutes(10)).isBefore(clock.instant());
    }
    private void removeEmpty(Path directory) throws IOException {
        try { Files.deleteIfExists(directory); } catch (DirectoryNotEmptyException ignored) { /* Unrecognized files are retained. */ }
    }
    private void secure(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Private upload permissions unavailable");
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(java.util.List.of(entry.build()));
        }
    }
    @Override public void close() throws IOException {
        // The next instance can reclaim this directory if the process died with unfinished files.
        cleanFiles(directory, 50, true);
        removeEmpty(multipartDirectory);
        boolean empty = true;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) if (!entry.equals(directory.resolve("owner.lock"))) empty = false;
        }
        Files.setLastModifiedTime(directory.resolve("owner.lock"), FileTime.from(clock.instant().minus(Duration.ofHours(1))));
        lock.release(); channel.close();
        if (empty) { Files.deleteIfExists(directory.resolve("owner.lock")); removeEmpty(directory); }
    }
}
