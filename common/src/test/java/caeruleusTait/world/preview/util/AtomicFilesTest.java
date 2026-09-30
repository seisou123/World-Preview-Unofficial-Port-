package caeruleusTait.world.preview.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void moveReplaceReplacesExistingTarget() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "new");
        Files.writeString(target, "old");

        AtomicFiles.moveReplace(source, target);

        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    @Test
    void moveReplaceCreatesTargetWhenMissing() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "data");

        AtomicFiles.moveReplace(source, target);

        assertEquals("data", Files.readString(target));
    }

    @Test
    void writeStringAtomicWritesContentAndLeavesNoTemp() throws IOException {
        Path target = tempDir.resolve("out.json");

        AtomicFiles.writeStringAtomic(target, "{\"a\":1}\n");

        assertEquals("{\"a\":1}\n", Files.readString(target));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")));
    }

    @Test
    void writeStringAtomicOverwritesExistingContent() throws IOException {
        Path target = tempDir.resolve("out.json");
        Files.writeString(target, "previous");

        AtomicFiles.writeStringAtomic(target, "updated");

        assertEquals("updated", Files.readString(target));
    }

    @Test
    void writeStringAtomicCleansUpTempOnFailure() throws IOException {
        // Target is a directory: the final move must fail
        Path target = tempDir.resolve("dir");
        Files.createDirectories(target);

        assertThrows(IOException.class, () -> AtomicFiles.writeStringAtomic(target, "x"));

        assertFalse(Files.exists(tempDir.resolve("dir.tmp")));
    }
}
