package conres.data;

import conres.interfaces.IFileRepository;
import conres.model.Result;

import java.io.*;
import java.nio.file.*;

/**
 * Local file system repository for shared resources.
 * Reads/writes ProductSpecification.txt.
 * No thread-safety here -- coordination is the engine layer's responsibility (C7 relaxation).
 *
 * Satisfies: FR3 (shared text file), FR7 (read), FR8 (write).
 */
public class LocalFileRepository implements IFileRepository {

    private final String basePath;

    public LocalFileRepository(String basePath) {
        this.basePath = basePath;
    }

    @Override
    public String readContents(String resourceId) throws IOException {
        Path path = Path.of(basePath, resourceId);
        return Files.readString(path);
    }

    @Override
    public Result<Void> writeContents(String resourceId, String data) {
        try {
            Path path = Path.of(basePath, resourceId);
            Files.writeString(path, data, StandardOpenOption.TRUNCATE_EXISTING);
            return Result.success();
        } catch (IOException e) {
            return Result.failure(e);
        }
    }

    /** Ensures the resource file exists, creating it with default content if needed. */
    public void ensureExists(String resourceId, String defaultContent) throws IOException {
        Path path = Path.of(basePath, resourceId);
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, defaultContent);
        }
    }
}
