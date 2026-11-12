package net.kdt.pojavlaunch.coint;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, strict edits to the known GTNH config format; never rewrites unrelated values. */
public final class CointConfigPatch {
    private CointConfigPatch() {}

    public static String disableBoolean(String text, String key) throws IOException {
        Pattern pattern = Pattern.compile("(?m)^([ \\t]*" + Pattern.quote(key)
                + "[ \\t]*=[ \\t]*)(true|false)([ \\t]*\\r?)$");
        Matcher matcher = pattern.matcher(text);
        int matches = 0;
        while (matcher.find()) matches++;
        if (matches != 1) throw new IOException("Expected one config option: " + key + ", found " + matches);
        return pattern.matcher(text).replaceFirst("$1false$3");
    }

    public static void disableInFile(File file, String... keys) throws IOException {
        if (!file.isFile()) return;
        String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String result = original;
        for (String key : keys) result = disableBoolean(result, key);
        if (result.equals(original)) return;
        File temporary = new File(file.getParentFile(), file.getName() + ".coint.tmp");
        Files.write(temporary.toPath(), result.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
