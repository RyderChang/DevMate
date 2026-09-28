package com.devmate.knowledge;

import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.UploadTempFiles;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class UploadTempFilesTest {
    @TempDir Path root;

    @Test void durableReservationsBoundSharedTemporaryCapacityAcrossInstances() throws Exception {
        var properties = new KnowledgeProperties(); properties.setTempDirectory(root.toString()); properties.setTempMaxBytes(150);
        try (var first = new UploadTempFiles(properties, Clock.systemUTC()); var second = new UploadTempFiles(properties, Clock.systemUTC())) {
            Path one = first.reserveBudget(40); Path two = second.reserveBudget(40);
            assertThatThrownBy(() -> first.reserveBudget(40)).isInstanceOf(java.io.IOException.class);
            first.release(one); second.release(two);
            Path replacement = second.reserveBudget(40); second.release(replacement);
        }
    }

    @Test void anOrphanIsReclaimedButLiveLockedInstancesAndSymlinksArePreserved() throws Exception {
        var properties = new KnowledgeProperties(); properties.setTempDirectory(root.resolve("managed").toString());
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        // Simulate a process that disappeared without invoking close(), including servlet spool.
        Files.createDirectories(root.resolve("managed"));
        Path orphan = Files.createDirectory(root.resolve("managed/devmate-upload-" + java.util.UUID.randomUUID()));
        Path marker = orphan.resolve("owner.lock"); Files.writeString(marker, "DevMate-knowledge-v1");
        var old = java.nio.file.attribute.FileTime.from(clock.instant().minusSeconds(1200));
        Files.setLastModifiedTime(marker, old);
        Path abandoned = orphan.resolve("upload-" + java.util.UUID.randomUUID() + ".bin"); Files.writeString(abandoned, "synthetic");
        Path multipart = Files.createDirectory(orphan.resolve("multipart"));
        Path spool = multipart.resolve("upload_synthetic.tmp"); Files.writeString(spool, "synthetic spool");
        Path unknown = orphan.resolve("unrelated.txt"); Files.writeString(unknown, "unrelated");
        Path external = root.resolve("outside.bin"); Files.writeString(external, "unrelated");
        try (var live = new UploadTempFiles(properties, clock); var sweeper = new UploadTempFiles(properties, clock)) {
            Path active = live.create(); Files.writeString(active, "synthetic active");
            Files.setLastModifiedTime(live.multipartDirectory().getParent().resolve("owner.lock"), old);
            Files.setLastModifiedTime(active, old);
            Path link = orphan.resolve("upload-00000000-0000-0000-0000-000000000000.bin");
            Files.createSymbolicLink(link, external);
            assertThat(Files.exists(abandoned)).isTrue(); assertThat(Files.exists(spool)).isTrue();
            sweeper.sweep(50);
            assertThat(Files.exists(abandoned)).isFalse(); assertThat(Files.exists(spool)).isFalse();
            assertThat(Files.exists(active)).isTrue(); assertThat(Files.readString(unknown)).isEqualTo("unrelated");
            assertThat(Files.readString(external)).isEqualTo("unrelated"); assertThat(Files.isSymbolicLink(link)).isTrue();
            live.release(active); Files.delete(link);
        }
    }

    @Test void applicationFilesUseOwnerOnlyPermissions() throws Exception {
        var properties = new KnowledgeProperties(); properties.setTempDirectory(root.toString());
        try (var files = new UploadTempFiles(properties, Clock.systemUTC())) {
            Path file = files.create();
            if (Files.getFileStore(file).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
                assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            } else {
                var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
                var owner = acl.getOwner();
                assertThat(acl.getAcl()).allMatch(value -> value.principal().equals(owner));
            }
            files.release(file);
        }
    }
}
