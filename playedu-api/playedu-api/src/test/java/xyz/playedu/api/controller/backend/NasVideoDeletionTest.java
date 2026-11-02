package xyz.playedu.api.controller.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.playedu.api.request.backend.ResourceDestroyMultiRequest;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.config.PlayEduConfig;
import xyz.playedu.common.service.AppConfigService;
import xyz.playedu.common.util.S3Util;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.service.ResourceExtraService;
import xyz.playedu.resource.service.ResourceService;

class NasVideoDeletionTest {
    @TempDir Path directory;

    @Test void removingSingleOrMultipleReferencesNeverDeletesNasSourceOrCallsS3() throws Exception {
        Path original = Files.writeString(directory.resolve("lesson.mp4"), "keep-original");
        Resource entry = new Resource();
        entry.setId(77);
        entry.setDisk("nas");
        entry.setPath("lesson.mp4");
        entry.setType("VIDEO");
        ResourceService resources = mock(ResourceService.class);
        ResourceExtraService extras = mock(ResourceExtraService.class);
        BackendBus backend = mock(BackendBus.class);
        when(backend.isSuperAdmin()).thenReturn(true);
        when(resources.findOrFail(77)).thenReturn(entry);
        when(resources.chunks(anyList())).thenReturn(List.of(entry));
        ResourceController controller = new ResourceController();
        ReflectionTestUtils.setField(controller, "resourceService", resources);
        ReflectionTestUtils.setField(controller, "resourceExtraService", extras);
        ReflectionTestUtils.setField(controller, "backendBus", backend);
        ReflectionTestUtils.setField(controller, "appConfigService", mock(AppConfigService.class));
        ReflectionTestUtils.setField(controller, "playEduConfig", mock(PlayEduConfig.class));
        try (var calls = mockConstruction(S3Util.class)) {
            controller.destroy(77);
            ResourceDestroyMultiRequest request = new ResourceDestroyMultiRequest();
            request.setIds(List.of(77));
            controller.multiDestroy(request);
            for (S3Util s3 : calls.constructed()) {
                verify(s3, never()).removeByPath(anyString());
            }
        }
        verify(resources, times(2)).removeById(77);
        verify(extras, times(2)).removeByRid(77);
        assertEquals("keep-original", Files.readString(original));
    }
}
