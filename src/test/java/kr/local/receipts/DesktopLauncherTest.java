package kr.local.receipts;

import kr.local.receipts.desktop.DesktopLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 사용자 장부와 분리되는 실행 옵션 및 사용 중인 포트의 회피를 확인합니다. */
class DesktopLauncherTest {
    @Test void explicitTestDataHomeKeepsUserDataSeparate(@TempDir Path temporary) {
        var options=DesktopLauncher.Options.parse(new String[]{"--data-home="+temporary,"--no-browser","--port=0"});
        assertEquals(temporary.toAbsolutePath().normalize(),options.home());assertTrue(options.noBrowser());
        assertEquals(0,options.preferredPort());assertFalse(options.quit());
        assertThrows(IllegalArgumentException.class,()->DesktopLauncher.Options.parse(new String[]{"--port=65536"}));
        assertThrows(IllegalArgumentException.class,()->DesktopLauncher.Options.parse(new String[]{"--unknown"}));
    }
    @Test void occupiedPortIsNeverReused()throws Exception {
        try(var occupied=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) {
            int selected=DesktopLauncher.availablePort(occupied.getLocalPort());
            assertNotEquals(occupied.getLocalPort(),selected);
            try(var available=new ServerSocket(selected,0,InetAddress.getByName("127.0.0.1"))) { assertEquals(selected,available.getLocalPort()); }
        }
    }
}
