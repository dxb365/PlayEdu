package xyz.playedu.resource.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.mock.env.MockEnvironment;

class NasVideoStorageTest {
    @TempDir Path directory;
    private static final String KEY = "isolated-nas-test-secret-key-01234567890123456789";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneOffset.UTC);

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("TEACHING_NAS_VIDEO_ROOT", directory.toString())
                .withProperty("TEACHING_NAS_PUBLIC_URL", "https://classroom.example:8443/api/nas-media")
                .withProperty("TEACHING_NAS_ADMIN_ORIGIN", "http://192.168.3.9:9900")
                .withProperty("sa-token.jwt-secret-key", KEY);
    }

    @Test void signedLinksAreBoundToResourceModeAndExpiration() {
        NasVideoStorage storage = new NasVideoStorage(environment(), CLOCK);
        Map<String, String> query = query(storage.signedUrl(12, "nas", false));
        long expires = Long.parseLong(query.get("expires"));
        String signature = query.get("signature");
        assertTrue(storage.verify(12, "original", expires, signature));
        assertFalse(storage.verify(13, "original", expires, signature));
        assertFalse(storage.verify(12, "download", expires, signature));
        assertFalse(storage.verify(12, "hls", expires, signature));
        assertFalse(storage.verify(12, "original", expires + 1, signature));
        assertFalse(storage.verify(12, null, expires, signature));
        assertFalse(storage.verify(12, "original", expires, "0".repeat(64)));
        assertFalse(storage.verify(12, "original", 1, signature));
        NasVideoStorage later = new NasVideoStorage(environment(),
                Clock.offset(CLOCK, java.time.Duration.ofSeconds(NasVideoStorage.LINK_SECONDS + 1)));
        assertFalse(later.verify(12, "original", expires, signature));
    }

    @Test void OriginalAndDownloadLinksUseSeparateSignatures() {
        NasVideoStorage storage = new NasVideoStorage(environment(), CLOCK);
        String original = storage.signedUrl(12, "nas", false);
        assertTrue(original.contains("/12/file?"));
        assertEquals("original", query(original).get("kind"));
        String download = storage.signedUrl(12, "nas", true);
        assertTrue(download.contains("/12/file?"));
        assertEquals("download", query(download).get("kind"));
        assertNotEquals(query(original).get("signature"), query(download).get("signature"));
    }

    @Test void pathTraversalAbsolutePathsAndControlCharactersAreRejected() throws Exception {
        NasVideoStorage storage = new NasVideoStorage(environment(), CLOCK);
        Files.createDirectory(directory.resolve("章节"));
        Path video = Files.writeString(directory.resolve("章节/第一课.mp4"), "video");
        assertEquals(video.toRealPath(), storage.resolve("章节/第一课.mp4", false));
        assertEquals("章节/第一课.mp4", storage.relative(video));
        for (String path : List.of("../outside", "章节/../../outside", "/etc/passwd",
                "章节/./第一课.mp4", "..\\outside", "file:/etc/passwd", "bad\nname", "bad\u0000name")) {
            assertThrows(IOException.class, () -> storage.resolve(path, false), path);
        }
        assertThrows(IOException.class, () -> storage.resolve("章节", false));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void symbolicLinksCannotEscapeTheLibrary() throws Exception {
        NasVideoStorage storage = new NasVideoStorage(environment(), CLOCK);
        Path outside = Files.createTempDirectory("nas-outside-");
        try {
            Files.writeString(outside.resolve("secret.mp4"), "not-in-library");
            Files.createSymbolicLink(directory.resolve("linked"), outside);
            Files.createSymbolicLink(directory.resolve("file.mp4"), outside.resolve("secret.mp4"));
            assertThrows(IOException.class, () -> storage.resolve("linked/secret.mp4", false));
            assertThrows(IOException.class, () -> storage.resolve("file.mp4", false));
            assertThrows(IOException.class, () -> storage.open("linked/secret.mp4"));
            assertThrows(IOException.class, () -> storage.open("file.mp4"));
        } finally {
            Files.deleteIfExists(outside.resolve("secret.mp4"));
            Files.deleteIfExists(outside);
        }
    }

    @Test void featureIsDisabledWithoutMountAndRequiresStrongSigningWhenEnabled() {
        NasVideoStorage disabled = new NasVideoStorage(new MockEnvironment(), CLOCK);
        assertFalse(disabled.isEnabled());
        assertEquals("", disabled.signedUrl(1, "nas", false));
        assertFalse(disabled.verify(1, "original", 1, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new NasVideoStorage(environment().withProperty("sa-token.jwt-secret-key", "weak"), CLOCK));
        assertThrows(IllegalArgumentException.class,
                () -> new NasVideoStorage(environment().withProperty("TEACHING_NAS_PUBLIC_URL",
                        "http://classroom.example/api/nas-media"), CLOCK));
        assertArrayEquals(new String[] {"https://classroom.example:8443", "http://192.168.3.9:9900"},
                new NasVideoStorage(environment(), CLOCK).allowedOrigins());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> fields = new HashMap<>();
        for (String entry : URI.create(url).getRawQuery().split("&")) {
            String[] parts = entry.split("=", 2);
            fields.put(parts[0], parts[1]);
        }
        return fields;
    }
}
