package xyz.playedu.api.controller.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.mockito.ArgumentMatchers;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.context.BCtx;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.CategoryService;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.media.NasVideoProbe;
import xyz.playedu.resource.media.NasVideoStorage;
import xyz.playedu.resource.service.ResourceExtraService;
import xyz.playedu.resource.service.ResourceService;

class NasVideoControllerTest {
    @TempDir Path directory;
    NasVideoController controller;
    ResourceService resources;
    ResourceExtraService extras;
    BackendBus backend;
    NasVideoProbe probe;
    PlatformTransactionManager transactions;
    final byte[] original = "original-nas-video-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach void setup() throws Exception {
        Files.write(directory.resolve("第一课.mp4"), original);
        Files.createDirectory(directory.resolve("子目录"));
        NasVideoStorage storage = new NasVideoStorage(new MockEnvironment()
                .withProperty("TEACHING_NAS_VIDEO_ROOT", directory.toString())
                .withProperty("TEACHING_NAS_PUBLIC_URL", "https://classroom.example:8443/api/nas-media")
                .withProperty("sa-token.jwt-secret-key", "isolated-nas-test-secret-01234567890123456789"));
        resources = mock(ResourceService.class);
        extras = mock(ResourceExtraService.class);
        backend = mock(BackendBus.class);
        probe = mock(NasVideoProbe.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(backend.isSuperAdmin()).thenReturn(true);
        when(resources.list(ArgumentMatchers.<Wrapper<Resource>>any())).thenReturn(List.of());
        when(probe.inspect("第一课.mp4")).thenReturn(
                new NasVideoProbe.ProbeInfo(8.25, "h264", "aac", "mp4", original.length, 1));
        when(resources.create(anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyInt(), anyInt())).thenAnswer(call -> {
                    Resource created = new Resource();
                    created.setId(77);
                    created.setPath(call.getArgument(7));
                    return created;
                });
        controller = new NasVideoController(storage, probe, backend, resources, extras,
                mock(CategoryService.class), transactions);
        BCtx.setId(1);
    }

    @AfterEach void clear() {
        BCtx.remove();
    }

    @Test void importRegistersMetadataWithoutWritingOrCopyingSource() throws Exception {
        Map<?, ?> result = (Map<?, ?>) controller.importVideo(
                new NasVideoController.ImportRequest("第一课.mp4", List.of())).getData();
        assertEquals(true, result.get("created"));
        assertEquals("nas", result.get("disk"));
        verify(resources).create(eq(1), eq(""), eq("VIDEO"), eq("第一课"), eq("mp4"),
                eq((long) original.length), eq("nas"), eq("第一课.mp4"), eq(0), eq(0));
        verify(extras).create(eq(77), anyInt(), eq(0));
        verify(transactions).commit(any());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("第一课.mp4")));
        try (var entries = Files.list(directory)) {
            assertEquals(2, entries.count());
        }
    }

    @Test void incompatibleVideoPreservesOriginalAndReturnsPreviewWarning() throws Exception {
        when(probe.inspect("第一课.mp4")).thenReturn(
                new NasVideoProbe.ProbeInfo(8.25, "hevc", "ac3", "mp4", original.length, 1));
        Map<?, ?> result = (Map<?, ?>) controller.importVideo(
                new NasVideoController.ImportRequest("第一课.mp4", List.of())).getData();
        assertEquals("nas", result.get("disk"));
        assertFalse(((String) result.get("warning")).isEmpty());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("第一课.mp4")));
    }

    @Test void duplicateImportKeepsExistingResourceAndLearningIdentity() throws Exception {
        Resource existing = new Resource();
        existing.setId(66);
        existing.setPath("第一课.mp4");
        existing.setName("第一课");
        existing.setDisk("nas");
        when(resources.list(ArgumentMatchers.<Wrapper<Resource>>any())).thenReturn(List.of(existing));
        Map<?, ?> result = (Map<?, ?>) controller.importVideo(
                new NasVideoController.ImportRequest("第一课.mp4", List.of())).getData();
        assertEquals(false, result.get("created"));
        assertEquals(66, result.get("id"));
        verify(resources, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(extras);
    }

    @Test void ordinaryAdministratorsCannotBrowseOrImportTheNasLibrary() throws Exception {
        when(backend.isSuperAdmin()).thenReturn(false);
        Map<?, ?> status = (Map<?, ?>) controller.status().getData();
        assertEquals(false, status.get("enabled"));
        assertThrows(ServiceException.class, () -> controller.browse("", "", 1, 50));
        assertThrows(ServiceException.class, () -> controller.importVideo(
                new NasVideoController.ImportRequest("第一课.mp4", List.of())));
        verifyNoInteractions(resources, probe, extras);
    }

    @Test void traversalDoesNotReachTheProbeOrDatabase() {
        assertThrows(ServiceException.class, () -> controller.importVideo(
                new NasVideoController.ImportRequest("../private.mp4", List.of())));
        assertThrows(ServiceException.class, () -> controller.browse("../", "", 1, 50));
        verifyNoInteractions(resources, probe, extras);
    }
}
