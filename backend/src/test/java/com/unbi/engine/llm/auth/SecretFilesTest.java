package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretFilesTest {
    @Test
    void replacementIsPrivateAndReadableAfterRestart(@TempDir Path root) throws Exception {
        var directory = root.resolve("credentials");
        SecretFiles.directory(directory);
        var file = directory.resolve("key.json");
        SecretFiles.write(file, "old".getBytes());
        SecretFiles.write(file, "rotated".getBytes());
        assertThat(new String(SecretFiles.read(file))).isEqualTo("rotated");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void symlinkCannotReplaceAnUnrelatedFile(@TempDir Path root) throws Exception {
        var target = root.resolve("unrelated");
        Files.writeString(target, "keep");
        var link = root.resolve("credential");
        Files.createSymbolicLink(link, target);
        assertThatThrownBy(() -> SecretFiles.write(link, "secret".getBytes())).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(target)).isEqualTo("keep");
    }

    @Test
    void refusesToReadPublicSecretFiles(@TempDir Path root) throws Exception {
        var file = root.resolve("credential");
        SecretFiles.write(file, "secret".getBytes());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> SecretFiles.read(file)).isInstanceOf(java.io.IOException.class);
    }
}
