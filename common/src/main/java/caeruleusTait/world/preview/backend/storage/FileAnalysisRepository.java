package caeruleusTait.world.preview.backend.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import caeruleusTait.world.preview.util.AtomicFiles;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

public final class FileAnalysisRepository implements AnalysisRepository {
    private static final int MAGIC = 0x57504131;

    private static final Gson GSON = new GsonBuilder().create();

    @Override
    public Optional<AnalysisTaskSnapshot> readTask(Path root, String taskId) {
        return readJson(root.resolve("task-" + taskId + ".json"), AnalysisTaskSnapshot.class);
    }

    @Override
    public void writeTask(Path root, AnalysisTaskSnapshot snapshot) {
        writeJson(root.resolve("task-" + snapshot.taskId() + ".json"), snapshot);
    }

    @Override
    public Optional<AnalysisResultSnapshot> readResult(Path root, String taskId) {
        return readJson(root.resolve("result-" + taskId + ".json"), AnalysisResultSnapshot.class);
    }

    @Override
    public void writeResult(Path root, AnalysisResultSnapshot snapshot) {
        writeJson(root.resolve("result-" + snapshot.taskId() + ".json"), snapshot);
    }

    @Override
    public Optional<PreviewStorage> readPreview(Path file, CacheFileHeader expected, int yMin, int yMax) {
        if (!Files.exists(file)) return Optional.empty();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) return Optional.empty();
            CacheFileHeader actual = new CacheFileHeader(input.readInt(), input.readUTF(), input.readLong());
            if (!expected.matches(actual) || actual.payloadLength() <= 0) return Optional.empty();
            PreviewStorage storage = PreviewStorage.readBinary(input, yMin, yMax);
            return Optional.of(storage);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void writePreview(Path file, CacheFileHeader header, PreviewStorage storage) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            byte[] payload;
            try (var bytes = new java.io.ByteArrayOutputStream(); DataOutputStream dos = new DataOutputStream(bytes)) {
                storage.writeBinary(dos);
                dos.flush();
                payload = bytes.toByteArray();
            }
            CacheFileHeader actual = new CacheFileHeader(header.formatVersion(), header.stableSignature(), payload.length);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                output.writeInt(MAGIC);
                output.writeInt(actual.formatVersion());
                output.writeUTF(actual.stableSignature());
                output.writeLong(actual.payloadLength());
                output.write(payload);
            }
            AtomicFiles.moveReplace(temp, file);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new RuntimeException("Failed to write preview cache", e);
        }
    }

    @Override
    public void removeTask(Path root, String taskId) {
        deleteQuietly(root.resolve("task-" + taskId + ".json"));
        deleteQuietly(root.resolve("result-" + taskId + ".json"));
    }

    private static <T> Optional<T> readJson(Path file, Class<T> type) {
        if (!Files.exists(file)) return Optional.empty();
        try {
            T value = GSON.fromJson(Files.readString(file), type);
            return Optional.ofNullable(value);
        } catch (RuntimeException | IOException e) {
            Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt-" + UUID.randomUUID());
            try { Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            return Optional.empty();
        }
    }

    private static void writeJson(Path file, Object value) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(temp, GSON.toJson(value));
            AtomicFiles.moveReplace(temp, file);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new RuntimeException("Failed to write analysis JSON", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
