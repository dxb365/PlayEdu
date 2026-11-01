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
package xyz.playedu.api.checks;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Teaching customization (2026-11-01): additive, repeatable initialization. */
@Component
@Order(20000)
public class TeachingSetup implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    public TeachingSetup(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void run(String... args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS teaching_playback_positions ("
            + "user_id INT NOT NULL,course_id INT NOT NULL,hour_id INT NOT NULL,position INT NOT NULL DEFAULT 0,"
            + "client_id VARCHAR(64) NOT NULL,sequence_no BIGINT NOT NULL,updated_at DATETIME(6) NOT NULL,"
            + "PRIMARY KEY(user_id,course_id,hour_id), INDEX user_latest(user_id,updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addConfig("player.disabled_speed", "禁止倍速播放", "0");
        addConfig("player.auto_resume", "自动恢复上次学习", "1");
        seedStorage("s3.endpoint", "TEACHING_S3_PUBLIC_ENDPOINT");
        seedStorage("s3.access_key", "TEACHING_S3_ACCESS_KEY");
        seedStorage("s3.secret_key", "TEACHING_S3_SECRET_KEY");
        seedStorage("s3.bucket", "TEACHING_S3_BUCKET");
        seedStorage("s3.region", "TEACHING_S3_REGION");
    }
    private void seedStorage(String key, String env) {
        String value=System.getenv(env);
        if (value != null && !value.isBlank()) {
            jdbc.update("UPDATE app_config SET key_value=? WHERE key_name=? AND (key_value IS NULL OR key_value='')", value, key);
        }
    }
    private void addConfig(String key, String name, String value) {
        jdbc.update("INSERT IGNORE INTO app_config (group_name,name,sort,field_type,key_name,key_value,is_private,is_hidden,created_at) VALUES ('播放器',?,0,'switch',?,?,0,0,CURRENT_TIMESTAMP)", name, key, value);
    }
}
