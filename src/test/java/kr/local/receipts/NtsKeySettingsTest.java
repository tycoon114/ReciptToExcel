package kr.local.receipts;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NtsKeySettingsTest {
    @TempDir Path directory;
    @Test void savesImmediatelyAndRestoresWithPriorityOverLegacySetting() throws Exception {
        Path file=directory.resolve("settings.properties");
        var settings=new NtsKeySettings(file,"legacy");
        assertEquals("legacy",settings.key());
        settings.save("new%2Bkey%2F%3D");
        assertEquals("new%2Bkey%2F%3D",settings.key());
        assertEquals(settings.key(),new NtsKeySettings(file,"other").key());
        try(var files=Files.list(directory)){assertEquals(1,files.count());}
    }
    @Test void failedWriteOrInvalidInputKeepsCurrentKey() throws Exception {
        Path blocked=directory.resolve("blocked");Files.writeString(blocked,"not a directory");
        var settings=new NtsKeySettings(blocked.resolve("settings.properties"),"legacy");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->settings.save("replacement"));
        assertEquals("legacy",settings.key());
        for(String invalid:new String[]{""," ","key\nsecond","key&other=value"})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->settings.save(invalid));
        assertEquals("legacy",settings.key());
    }
}
