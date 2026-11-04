package xyz.playedu.api.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.AppConfigService;
import xyz.playedu.course.domain.CourseChapter;
import xyz.playedu.course.domain.CourseHour;
import xyz.playedu.course.domain.UserCourseHourRecord;
import xyz.playedu.course.service.CourseChapterService;
import xyz.playedu.course.service.CourseHourService;
import xyz.playedu.course.service.UserCourseHourRecordService;

@Service
public class SequentialLearningService {
    private final AppConfigService config;
    private final CourseChapterService chapters;
    private final CourseHourService hours;
    private final UserCourseHourRecordService records;

    public SequentialLearningService(AppConfigService config, CourseChapterService chapters,
            CourseHourService hours, UserCourseHourRecordService records) {
        this.config = config;
        this.chapters = chapters;
        this.hours = hours;
        this.records = records;
    }

    public boolean isEnabled() {
        return "1".equals(config.keyValues().get("player.sequential_learning"));
    }

    public List<CourseHour> orderedHours(Integer courseId) {
        List<CourseChapter> courseChapters = new ArrayList<>(chapters.getChaptersByCourseId(courseId));
        courseChapters.sort(Comparator.comparing(CourseChapter::getSort)
                .thenComparing(CourseChapter::getId));
        Map<Integer, Integer> order = new HashMap<>();
        order.put(0, -1);
        for (int index = 0; index < courseChapters.size(); index++) {
            order.put(courseChapters.get(index).getId(), index);
        }
        List<CourseHour> result = new ArrayList<>(hours.getHoursByCourseId(courseId));
        result.sort(Comparator.comparingInt((CourseHour hour) ->
                    order.getOrDefault(hour.getChapterId(), Integer.MAX_VALUE))
                .thenComparing(CourseHour::getSort).thenComparing(CourseHour::getId));
        return result;
    }

    public List<Integer> lockedHourIds(Integer userId, Integer courseId) {
        if (!isEnabled()) return List.of();
        return lockedHourIds(orderedHours(courseId), records.getRecords(userId, courseId));
    }

    public List<Integer> lockedHourIds(List<CourseHour> orderedHours,
            List<UserCourseHourRecord> learningRecords) {
        Set<Integer> completed = new HashSet<>();
        for (UserCourseHourRecord record : learningRecords) {
            if (Integer.valueOf(1).equals(record.getIsFinished())) completed.add(record.getHourId());
        }
        List<Integer> locked = new ArrayList<>();
        boolean unfinished = false;
        for (CourseHour hour : orderedHours) {
            if (completed.contains(hour.getId())) continue;
            if (unfinished) locked.add(hour.getId());
            unfinished = true;
        }
        return locked;
    }

    public void requireUnlocked(Integer userId, Integer courseId, Integer hourId) {
        if (lockedHourIds(userId, courseId).contains(hourId)) {
            throw new ServiceException("请先学完前面的课时，再学习本节");
        }
    }
}
