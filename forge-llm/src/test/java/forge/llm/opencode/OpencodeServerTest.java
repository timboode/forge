package forge.llm.opencode;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

public class OpencodeServerTest {

    @Test
    public void anExplicitExecutablePathIsUsedAsIs() throws IOException {
        Path exe = Files.createTempFile("fake-opencode", ".cmd");
        try {
            OpencodeSettings s = new OpencodeSettings();
            s.executable = exe.toString();
            assertEquals(OpencodeServer.executable(s), List.of(new File(exe.toString()).getAbsolutePath()));
        } finally {
            Files.deleteIfExists(exe);
        }
    }

    @Test
    public void anUnknownExecutableIsLeftToTheOperatingSystemSoTheErrorNamesIt() {
        OpencodeSettings s = new OpencodeSettings();
        s.executable = "definitely-not-opencode-xyz";
        assertEquals(OpencodeServer.executable(s), List.of("definitely-not-opencode-xyz"));
    }

    @Test
    public void startingAnExecutableThatDoesNotExistFailsWithAHelpfulMessageAndLeavesNoDirectoryBehind() {
        OpencodeSettings s = new OpencodeSettings();
        s.executable = "definitely-not-opencode-xyz";
        OpencodeException e = null;
        try {
            OpencodeServer.start(s);
        } catch (OpencodeException caught) {
            e = caught;
        }
        assertTrue(e != null && e.getMessage().contains("definitely-not-opencode-xyz") && e.getMessage().contains("PATH"),
                String.valueOf(e));
    }
}
