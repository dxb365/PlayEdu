package fun.learning5927.classroom;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServerAddressTest {
    @Test public void normalizesHttpsOrigins() {
        assertEquals("https://www.5927.fun:8443/", ServerAddress.normalize(" HTTPS://WWW.5927.FUN:8443 "));
        assertEquals("https://classroom.example/", ServerAddress.normalize("https://classroom.example"));
        assertEquals("https://192.168.1.5:8443/", ServerAddress.normalize("https://192.168.1.5:8443/"));
    }

    @Test public void rejectsCredentialsInsecureSchemesAndNonOrigins() {
        for (String address : new String[]{"http://example.com", "javascript:alert(1)", "file:///sdcard/test.html",
                "https://user:secret@example.com", "https://example.com/path", "https://example.com/?token=x",
                "https://example.com/#/login", "https://example.com:0", "https://example.com:65536", "https://", ""}) {
            assertThrows(address, IllegalArgumentException.class, () -> ServerAddress.normalize(address));
        }
    }

    @Test public void restrictsNavigationToExactOrigin() {
        String origin = "https://www.5927.fun:8443/";
        assertTrue(ServerAddress.isInternal(origin, "https://www.5927.fun:8443/course/1/hour/2"));
        for (String link : new String[]{"https://www.5927.fun:9443/videos/a", "http://www.5927.fun:8443/",
                "https://www.5927.fun.evil.example:8443/", "https://www.5927.fun:8443@evil.example/",
                "file:///sdcard/a", "intent://test", "javascript:alert(1)", "about:blank"}) {
            assertFalse(link, ServerAddress.isInternal(origin, link));
        }
        assertTrue(ServerAddress.isInternal("https://example.com/", "https://EXAMPLE.com:443/login"));
    }

    @Test public void permitsOnlyNormalExternalWebLinks() {
        assertTrue(ServerAddress.isExternalWebLink("https://playedu.xyz/"));
        assertFalse(ServerAddress.isExternalWebLink("intent://package"));
        assertFalse(ServerAddress.isExternalWebLink("https://user:password@example.com/"));
    }
}
