package net.kdt.pojavlaunch.coint;

import net.kdt.pojavlaunch.utils.ZipUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public class ZipImportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File zip(String... paths) throws IOException {
        File archive = temporary.newFile();
        try (ZipOutputStream stream = new ZipOutputStream(new FileOutputStream(archive))) {
            for (String path : paths) {
                stream.putNextEntry(new ZipEntry(path));
                stream.write("new".getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
            }
        }
        return archive;
    }

    @Test public void extractsOnlyRequestedPrefix() throws IOException {
        File destination = temporary.newFolder("pack");
        try (ZipFile archive = new ZipFile(zip("overrides/config/a.cfg", "ignored.txt"))) {
            ZipUtils.zipExtract(archive, "overrides/", destination);
        }
        assertEquals("new", new String(Files.readAllBytes(new File(destination, "config/a.cfg").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(destination, "ignored.txt").exists());
    }

    @Test public void rejectsTraversalBeforeOverwritingExistingFiles() throws IOException {
        File destination = temporary.newFolder("pack");
        File existing = new File(destination, "existing.txt");
        Files.write(existing.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        try (ZipFile archive = new ZipFile(zip("overrides/existing.txt", "overrides/../escaped.txt"))) {
            try {
                ZipUtils.zipExtract(archive, "overrides/", destination);
                fail("Traversal was accepted");
            } catch (IOException expected) {
                assertEquals("keep", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
                assertFalse(new File(temporary.getRoot(), "escaped.txt").exists());
            }
        }
    }
}
