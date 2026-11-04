package xyz.playedu.api.request.backend;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CourseHourAutoSortRequest(
        @NotNull @Pattern(regexp = "name|modified_at") String field,
        @NotNull @Pattern(regexp = "asc|desc") String order,
        Integer chapter_id) {}
