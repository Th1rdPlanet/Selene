package ru.moonlightproject.dev.selene.core;

import java.io.IOException;
import java.nio.file.*;

public final class ConfigFiles {
    private ConfigFiles() {}
    public static void writeAtomic(Path target, String contents) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".selene-", ".tmp");
        try {
            Files.writeString(temporary, contents);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
