package net.kdt.pojavlaunch.coint;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import java.io.IOException;

public class CointConfigPatchTest {
    @Test public void preservesCommentsWhitespaceAndCrLf() throws IOException {
        String input = "# B:sharedContext=true\r\n    B:sharedContext = true  \r\nB:other=true\r\n";
        assertEquals("# B:sharedContext=true\r\n    B:sharedContext = false  \r\nB:other=true\r\n",
                CointConfigPatch.disableBoolean(input, "B:sharedContext"));
    }
    @Test public void isIdempotentForPropertiesFiles() throws IOException {
        String text = "enabled=false\nrotate=false\n";
        assertEquals(text, CointConfigPatch.disableBoolean(text, "enabled"));
    }
    @Test(expected = IOException.class) public void rejectsAmbiguousOptions() throws IOException {
        CointConfigPatch.disableBoolean("enabled=true\nenabled=false\n", "enabled");
    }
    @Test(expected = IOException.class) public void doesNotMatchPartialOrCommentedKeys() throws IOException {
        CointConfigPatch.disableBoolean("# enabled=true\nother_enabled=true\n", "enabled");
    }
}
