package xyz.playedu.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.playedu.api.controller.frontend.HourController;
import xyz.playedu.api.request.frontend.CourseHourRecordRequest;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.context.FCtx;
import xyz.playedu.course.caches.UserCanSeeCourseCache;
import xyz.playedu.course.service.CourseHourService;
import xyz.playedu.course.service.CourseService;
import xyz.playedu.resource.service.ResourceService;

class SequentialHourAccessTest {
    @AfterEach void clearContext() {
        FCtx.remove();
    }

    @Test void lockedHoursCannotReadPlayDownloadSubtitlesOrWriteProgressThroughDirectRequests() throws Exception {
        FCtx.setId(7);
        HourController controller = new HourController();
        SequentialLearningService sequence = mock(SequentialLearningService.class);
        ResourceService resources = mock(ResourceService.class);
        ReflectionTestUtils.setField(controller, "sequence", sequence);
        ReflectionTestUtils.setField(controller, "courseService", mock(CourseService.class));
        ReflectionTestUtils.setField(controller, "hourService", mock(CourseHourService.class));
        ReflectionTestUtils.setField(controller, "resourceService", resources);
        ReflectionTestUtils.setField(controller, "userCanSeeCourseCache", mock(UserCanSeeCourseCache.class));
        doThrow(new ServiceException("locked")).when(sequence).requireUnlocked(any(), eq(9), eq(2));
        assertThrows(ServiceException.class, () -> controller.detail(9, 2));
        assertThrows(ServiceException.class, () -> controller.play(9, 2));
        assertThrows(ServiceException.class, () -> controller.subtitle(
                9, 2, "0".repeat(64), "main", new MockHttpServletResponse()));
        CourseHourRecordRequest request = new CourseHourRecordRequest();
        request.setDuration(20);
        assertThrows(ServiceException.class, () -> controller.record(9, 2, request));
        assertThrows(ServiceException.class, () -> controller.ping(9, 2));
        verify(sequence, times(5)).requireUnlocked(any(), eq(9), eq(2));
        verifyNoInteractions(resources);
    }
}
