package model;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the project directory, which holds config/ and results/. */
public final class Project {
    private Project() {}

    /**
     * The nearest directory, from the working directory upwards, that holds pom.xml. The
     * simulation runs in target/, so relative paths are resolved against this instead.
     */
    public static Path directory() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path dir = workingDirectory; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("pom.xml"))) return dir;
        }
        return workingDirectory;
    }
}
