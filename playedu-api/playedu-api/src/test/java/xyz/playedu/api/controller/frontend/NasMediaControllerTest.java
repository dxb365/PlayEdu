package xyz.playedu.api.controller.frontend;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.media.NasVideoStorage;
import xyz.playedu.resource.service.ResourceService;

class NasMediaControllerTest {
    @TempDir Path directory;
    MockMvc mvc;
    NasVideoStorage storage;
    ResourceService resources;
    Map<String, String> query;
    final byte[] content = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach void setup() throws Exception {
        Files.write(directory.resolve("第一课.mp4"), content);
        storage = new NasVideoStorage(new MockEnvironment()
                .withProperty("TEACHING_NAS_VIDEO_ROOT", directory.toString())
                .withProperty("TEACHING_NAS_PUBLIC_URL", "https://classroom.example:8443/api/nas-media")
                .withProperty("sa-token.jwt-secret-key", "isolated-nas-test-secret-01234567890123456789"));
        resources = mock(ResourceService.class);
        Resource entry = new Resource();
        entry.setId(42);
        entry.setDisk("nas");
        entry.setPath("第一课.mp4");
        entry.setExtension("mp4");
        when(resources.getById(42)).thenReturn(entry);
        mvc = MockMvcBuilders.standaloneSetup(new NasMediaController(storage, resources)).build();
        query = query(storage.signedUrl(42, "nas", false));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void originalVideoSupportsFullOpenEndedAndSuffixRanges() throws Exception {
        mvc.perform(request(get("/nas-media/42/file")))
                .andExpect(status().isOk()).andExpect(content().bytes(content));
        mvc.perform(request(get("/nas-media/42/file")).header("Range", "bytes=5-9"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 5-9/36"))
                .andExpect(header().string("Content-Length", "5"))
                .andExpect(content().bytes(Arrays.copyOfRange(content, 5, 10)));
        mvc.perform(request(get("/nas-media/42/file")).header("Range", "bytes=30-"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(content, 30, 36)));
        mvc.perform(request(get("/nas-media/42/file")).header("Range", "bytes=-4"))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(content, 32, 36)));
    }

    @Test void headReturnsMetadataAndNoVideoBody() throws Exception {
        mvc.perform(request(head("/nas-media/42/file")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", "36"))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test void invalidSignaturesAreRejectedBeforeResourceLookup() throws Exception {
        query.put("signature", "0".repeat(64));
        mvc.perform(request(get("/nas-media/42/file"))).andExpect(status().isForbidden());
        verifyNoInteractions(resources);
        mvc.perform(request(get("/nas-media/43/file"))).andExpect(status().isForbidden());
    }

    @Test void invalidAndMultipleRangesAreRejected() throws Exception {
        mvc.perform(request(get("/nas-media/42/file")).header("Range", "bytes=1000-2000"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(request(get("/nas-media/42/file")).header("Range", "bytes=0-1,3-4"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */36"));
    }

    @Test void missingAndNonNasResourcesAreNotServed() throws Exception {
        when(resources.getById(42)).thenReturn(null);
        mvc.perform(request(get("/nas-media/42/file"))).andExpect(status().isNotFound());
        Resource s3 = new Resource();
        s3.setDisk("s3");
        when(resources.getById(42)).thenReturn(s3);
        mvc.perform(request(get("/nas-media/42/file"))).andExpect(status().isNotFound());
    }

    @Test void playbackSignatureCannotBeRepurposedForDownload() throws Exception {
        query.put("kind", "download");
        mvc.perform(request(get("/nas-media/42/file"))).andExpect(status().isForbidden());
        verifyNoInteractions(resources);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        query.forEach(builder::param);
        return builder;
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
