/*
 * Copyright (C) 2023 杭州白书科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.playedu.api.controller.frontend;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import xyz.playedu.api.service.PlaybackPositionService;
import xyz.playedu.common.context.FCtx;
import xyz.playedu.common.types.JsonResponse;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.course.caches.UserCanSeeCourseCache;
import xyz.playedu.course.service.CourseHourService;

/** Teaching customization (2026-11-01): resume only accessible courses for this user. */
@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {
    private final PlaybackPositionService positions;
    private final UserCanSeeCourseCache access;
    private final CourseHourService hours;
    public PlaybackController(PlaybackPositionService positions, UserCanSeeCourseCache access, CourseHourService hours) {
        this.positions=positions; this.access=access; this.hours=hours;
    }
    @GetMapping("/latest")
    public JsonResponse latest() {
        for (Map<String,Object> row : positions.latest(FCtx.getId())) {
            int courseId=((Number) row.get("course_id")).intValue();
            int hourId=((Number) row.get("hour_id")).intValue();
            try {
                access.check(FCtx.getId(),courseId,true);
                hours.findOrFail(hourId,courseId);
                return JsonResponse.data(row);
            } catch (ServiceException ignored) {
                // A deleted/withdrawn course must not prevent opening the site.
            }
        }
        return JsonResponse.data(null);
    }
}
