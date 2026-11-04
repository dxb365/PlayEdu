package xyz.playedu.api.service;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.text.Collator;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.playedu.api.request.backend.CourseHourAutoSortRequest;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.context.BCtx;
import xyz.playedu.common.exception.NotFoundException;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.course.domain.Course;
import xyz.playedu.course.domain.CourseHour;
import xyz.playedu.course.service.CourseHourService;
import xyz.playedu.course.service.CourseService;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.media.NasVideoStorage;
import xyz.playedu.resource.service.ResourceService;

@Service
public class CourseHourOrderingService {
    private final CourseService courses;
    private final CourseHourService hours;
    private final ResourceService resources;
    private final NasVideoStorage storage;
    private final BackendBus backend;

    public CourseHourOrderingService(CourseService courses, CourseHourService hours,
            ResourceService resources, NasVideoStorage storage, BackendBus backend) {
        this.courses = courses;
        this.hours = hours;
        this.resources = resources;
        this.storage = storage;
        this.backend = backend;
    }

    @Transactional(rollbackFor = Exception.class)
    public void sort(Integer courseId, CourseHourAutoSortRequest request) throws NotFoundException {
        Course course = courses.findOrFail(courseId);
        if (!backend.isSuperAdmin() && !Objects.equals(course.getAdminId(), BCtx.getId())) {
            throw new ServiceException("无权限操作");
        }
        if (!Set.of("name", "modified_at").contains(request.field())
                || !Set.of("asc", "desc").contains(request.order())) {
            throw new ServiceException("排序方式不支持");
        }
        List<CourseHour> selected = hours.getHoursByCourseId(courseId).stream()
                .filter(hour -> request.chapter_id() == null
                        || Objects.equals(hour.getChapterId(), request.chapter_id())).toList();
        Map<Integer, Long> times = new HashMap<>();
        if ("modified_at".equals(request.field()) && !selected.isEmpty()) {
            Map<Integer, Resource> resourceMap = resources.chunks(selected.stream()
                    .map(CourseHour::getRid).distinct().toList()).stream()
                    .collect(Collectors.toMap(Resource::getId, item -> item));
            for (CourseHour hour : selected) {
                Resource resource = resourceMap.get(hour.getRid());
                if (resource == null) throw new ServiceException("课时视频不存在，请先检查资源");
                long modified;
                if (NasVideoStorage.isNasDisk(resource.getDisk())) {
                    try {
                        modified = Files.getLastModifiedTime(storage.resolve(resource.getPath(), false)).toMillis();
                    } catch (IOException e) {
                        throw new ServiceException("无法读取视频修改时间：" + hour.getTitle());
                    }
                } else {
                    Date date = resource.getUpdatedAt() == null ? resource.getCreatedAt() : resource.getUpdatedAt();
                    if (date == null) throw new ServiceException("视频缺少修改时间：" + hour.getTitle());
                    modified = date.getTime();
                }
                times.put(hour.getId(), modified);
            }
        }
        Comparator<CourseHour> ordering = "name".equals(request.field())
                ? Comparator.comparing(CourseHour::getTitle, CourseHourOrderingService::compareNames)
                : Comparator.comparingLong(hour -> times.get(hour.getId()));
        if ("desc".equals(request.order())) ordering = ordering.reversed();
        ordering = ordering.thenComparing(CourseHour::getId);
        for (List<CourseHour> chapter : selected.stream()
                .collect(Collectors.groupingBy(CourseHour::getChapterId)).values()) {
            chapter.sort(ordering);
            hours.updateSort(chapter.stream().map(CourseHour::getId).toList(), courseId);
        }
    }

    static int compareNames(String left, String right) {
        Matcher a = Pattern.compile("[0-9]+|[^0-9]+").matcher(left == null ? "" : left);
        Matcher b = Pattern.compile("[0-9]+|[^0-9]+").matcher(right == null ? "" : right);
        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.SECONDARY);
        while (a.find()) {
            if (!b.find()) return 1;
            String x = a.group();
            String y = b.group();
            int result = x.matches("[0-9]+") && y.matches("[0-9]+")
                    ? new BigInteger(x).compareTo(new BigInteger(y)) : collator.compare(x, y);
            if (result != 0) return result;
        }
        return b.find() ? -1 : 0;
    }
}
