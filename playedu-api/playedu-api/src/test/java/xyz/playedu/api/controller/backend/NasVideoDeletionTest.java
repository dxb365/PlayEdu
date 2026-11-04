package xyz.playedu.api.controller.backend;

import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.playedu.api.request.backend.ResourceDestroyMultiRequest;
import xyz.playedu.api.service.ResourceBulkService;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.service.ResourceService;

class NasVideoDeletionTest {
    @Test void singleAndMultipleDeletionUseTheSameReferenceAwareService() throws Exception {
        Resource entry = new Resource();
        entry.setId(77);
        ResourceService resources = mock(ResourceService.class);
        when(resources.findOrFail(77)).thenReturn(entry);
        ResourceBulkService bulk = mock(ResourceBulkService.class);
        ResourceController controller = new ResourceController();
        ReflectionTestUtils.setField(controller, "resourceService", resources);
        ReflectionTestUtils.setField(controller, "bulkService", bulk);
        controller.destroy(77);
        ResourceDestroyMultiRequest request = new ResourceDestroyMultiRequest();
        request.setIds(List.of(77));
        controller.multiDestroy(request);
        verify(bulk, times(2)).delete(List.of(77));
    }
}
