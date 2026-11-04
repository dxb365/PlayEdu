package xyz.playedu.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.AppConfigService;
import xyz.playedu.course.domain.CourseChapter;
import xyz.playedu.course.domain.CourseHour;
import xyz.playedu.course.domain.UserCourseHourRecord;
import xyz.playedu.course.service.CourseChapterService;
import xyz.playedu.course.service.CourseHourService;
import xyz.playedu.course.service.UserCourseHourRecordService;

class SequentialLearningServiceTest {
    AppConfigService config;
    CourseChapterService chapters;
    CourseHourService hours;
    UserCourseHourRecordService records;
    SequentialLearningService service;

    @BeforeEach void setup() {
        config = mock(AppConfigService.class);
        chapters = mock(CourseChapterService.class);
        hours = mock(CourseHourService.class);
        records = mock(UserCourseHourRecordService.class);
        service = new SequentialLearningService(config, chapters, hours, records);
    }

    @Test void missingOrDisabledSettingPreservesExistingAccess() {
        when(config.keyValues()).thenReturn(Map.of());
        assertTrue(service.lockedHourIds(1, 9).isEmpty());
        service.requireUnlocked(1, 9, 200);
        verifyNoInteractions(chapters, hours, records);
    }

    @Test void chapterOrderPrecedesHourOrderAndIdsOnlyBreakTies() {
        when(chapters.getChaptersByCourseId(9)).thenReturn(List.of(chapter(3, 8), chapter(8, 1)));
        when(hours.getHoursByCourseId(9)).thenReturn(List.of(
                hour(20, 3, 0), hour(40, 8, 0), hour(30, 8, 0), hour(10, 3, 1)));
        assertEquals(List.of(30, 40, 20, 10), service.orderedHours(9).stream().map(CourseHour::getId).toList());
    }

    @Test void firstUnfinishedUnlocksAndPreviouslyCompletedHoursRemainReviewable() {
        List<CourseHour> course = List.of(hour(1, 0, 0), hour(2, 0, 1), hour(3, 0, 2), hour(4, 0, 3));
        assertEquals(List.of(2, 3, 4), service.lockedHourIds(course, List.of()));
        assertEquals(List.of(3, 4), service.lockedHourIds(course, List.of(record(1, true))));
        assertEquals(List.of(3), service.lockedHourIds(course, List.of(record(1, true), record(4, true))));
        assertEquals(List.of(2, 3, 4), service.lockedHourIds(course, List.of(record(1, false))));
        assertTrue(service.lockedHourIds(course, List.of(record(1, true), record(2, true),
                record(3, true), record(4, true))).isEmpty());
    }

    @Test void directAccessRequiresEarlierCompletionAndRechecksSavedProgress() {
        when(config.keyValues()).thenReturn(Map.of("player.sequential_learning", "1"));
        when(chapters.getChaptersByCourseId(9)).thenReturn(List.of());
        when(hours.getHoursByCourseId(9)).thenReturn(List.of(hour(1, 0, 0), hour(2, 0, 1)));
        when(records.getRecords(7, 9)).thenReturn(List.of(), List.of(record(1, true)));
        assertThrows(ServiceException.class, () -> service.requireUnlocked(7, 9, 2));
        assertDoesNotThrow(() -> service.requireUnlocked(7, 9, 2));
    }

    private static CourseHour hour(int id, int chapter, int sort) {
        CourseHour hour = new CourseHour();
        hour.setId(id);
        hour.setChapterId(chapter);
        hour.setSort(sort);
        return hour;
    }

    private static CourseChapter chapter(int id, int sort) {
        CourseChapter chapter = new CourseChapter();
        chapter.setId(id);
        chapter.setSort(sort);
        return chapter;
    }

    private static UserCourseHourRecord record(int hour, boolean finished) {
        UserCourseHourRecord record = new UserCourseHourRecord();
        record.setHourId(hour);
        record.setIsFinished(finished ? 1 : 0);
        return record;
    }
}
