package xyz.playedu.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.playedu.api.request.backend.CourseHourAutoSortRequest;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.course.domain.Course;
import xyz.playedu.course.domain.CourseHour;
import xyz.playedu.course.service.CourseHourService;
import xyz.playedu.course.service.CourseService;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.media.NasVideoStorage;
import xyz.playedu.resource.service.ResourceService;

class CourseHourOrderingServiceTest {
    CourseService courses = mock(CourseService.class);
    CourseHourService hours = mock(CourseHourService.class);
    ResourceService resources = mock(ResourceService.class);
    NasVideoStorage storage = mock(NasVideoStorage.class);
    BackendBus backend = mock(BackendBus.class);
    CourseHourOrderingService service = new CourseHourOrderingService(courses, hours, resources, storage, backend);
    @TempDir Path directory;

    @BeforeEach void setup() throws Exception {
        Course course = new Course();
        course.setId(7);
        course.setAdminId(88);
        when(courses.findOrFail(7)).thenReturn(course);
        when(backend.isSuperAdmin()).thenReturn(true);
        when(hours.getHoursByCourseId(7)).thenReturn(List.of(
                hour(1, 0, "第10课"), hour(2, 0, "第2课"), hour(3, 9, "第1课")));
    }

    @Test void numericNamesSortWithinChaptersBothDirections() throws Exception {
        service.sort(7, new CourseHourAutoSortRequest("name", "asc", null));
        verify(hours).updateSort(List.of(2, 1), 7);
        verify(hours).updateSort(List.of(3), 7);
        service.sort(7, new CourseHourAutoSortRequest("name", "desc", 0));
        verify(hours).updateSort(List.of(1, 2), 7);
        verifyNoInteractions(resources, storage);
    }

    @Test void modificationOrderUsesNasOriginalAndUploadedMetadata() throws Exception {
        Resource nas = resource(1, "nas", 9999);
        Resource upload = resource(2, "s3", 2000);
        Path source = Files.writeString(directory.resolve("test.mp4"), "fixture");
        Files.setLastModifiedTime(source, FileTime.fromMillis(1000));
        when(storage.resolve("video.mp4", false)).thenReturn(source);
        when(resources.chunks(List.of(1, 2))).thenReturn(List.of(nas, upload));
        service.sort(7, new CourseHourAutoSortRequest("modified_at", "asc", 0));
        verify(hours).updateSort(List.of(1, 2), 7);
        service.sort(7, new CourseHourAutoSortRequest("modified_at", "desc", 0));
        verify(hours).updateSort(List.of(2, 1), 7);
        assertEquals("fixture", Files.readString(source));
    }

    @Test void missingFileDoesNotSavePartialOrder() throws Exception {
        when(resources.chunks(List.of(1, 2))).thenReturn(List.of(resource(1, "nas", 0), resource(2, "s3", 10)));
        when(storage.resolve("video.mp4", false)).thenThrow(new java.io.IOException());
        assertThrows(ServiceException.class, () -> service.sort(7,
                new CourseHourAutoSortRequest("modified_at", "asc", 0)));
        verify(hours, never()).updateSort(anyList(), anyInt());
    }

    @Test void otherAdministratorsCannotReorderCourse() {
        when(backend.isSuperAdmin()).thenReturn(false);
        assertThrows(ServiceException.class, () -> service.sort(7,
                new CourseHourAutoSortRequest("name", "asc", null)));
        verifyNoInteractions(hours, resources, storage);
    }

    @Test void equalNamesKeepStableIdsAndLongNumbersDoNotOverflow() {
        assertEquals(0, CourseHourOrderingService.compareNames("第02课", "第2课"));
        assertTrue(CourseHourOrderingService.compareNames("第999999999999999999999课", "第10课") > 0);
    }

    private CourseHour hour(int id, int chapter, String title) {
        CourseHour item = new CourseHour();
        item.setId(id);
        item.setCourseId(7);
        item.setChapterId(chapter);
        item.setRid(id);
        item.setTitle(title);
        return item;
    }

    private Resource resource(int id, String disk, long time) {
        Resource item = new Resource();
        item.setId(id);
        item.setDisk(disk);
        item.setPath("video.mp4");
        item.setUpdatedAt(new Date(time));
        return item;
    }
}
