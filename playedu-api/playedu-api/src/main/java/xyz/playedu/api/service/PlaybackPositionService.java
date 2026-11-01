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
package xyz.playedu.api.service;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Teaching customization (2026-11-01): keep the resume cursor separate from completion. */
@Service
public class PlaybackPositionService {
    private final JdbcTemplate jdbc;
    public PlaybackPositionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> find(Integer userId, Integer courseId, Integer hourId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT course_id, hour_id, position FROM teaching_playback_positions WHERE user_id=? AND course_id=? AND hour_id=?",
            userId, courseId, hourId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> latest(Integer userId) {
        return jdbc.queryForList(
            "SELECT course_id, hour_id, position FROM teaching_playback_positions WHERE user_id=? ORDER BY updated_at DESC LIMIT 20", userId);
    }

    // Reject an older request from the same player (e.g. pagehide overtaking a periodic save).
    public synchronized void save(Integer userId, Integer courseId, Integer hourId, int position,
                                  String clientId, long sequence) {
        if (clientId == null || clientId.length() > 64 || sequence < 0) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT client_id, sequence_no FROM teaching_playback_positions WHERE user_id=? AND course_id=? AND hour_id=?",
            userId, courseId, hourId);
        if (!rows.isEmpty() && clientId.equals(rows.get(0).get("client_id"))
                && ((Number) rows.get(0).get("sequence_no")).longValue() >= sequence) return;
        jdbc.update("INSERT INTO teaching_playback_positions (user_id,course_id,hour_id,position,client_id,sequence_no,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6)) "
            + "ON DUPLICATE KEY UPDATE position=VALUES(position), client_id=VALUES(client_id), sequence_no=VALUES(sequence_no), updated_at=CURRENT_TIMESTAMP(6)",
            userId, courseId, hourId, Math.max(0, position), clientId, sequence);
    }
}
