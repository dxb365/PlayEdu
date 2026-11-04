package xyz.playedu.api.checks;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(20001)
public class ResourceModifiedSetup implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    public ResourceModifiedSetup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        if (!hasColumn()) {
            try {
                jdbc.execute("ALTER TABLE resource ADD COLUMN updated_at DATETIME(6) NULL");
            } catch (DataAccessException error) {
                if (!hasColumn()) throw error;
            }
        }
        jdbc.update("UPDATE resource SET updated_at=COALESCE(created_at,CURRENT_TIMESTAMP(6)) WHERE updated_at IS NULL");
        String extra = jdbc.queryForObject(
                "SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resource' AND COLUMN_NAME='updated_at'",
                String.class);
        if (extra == null || !extra.toLowerCase(java.util.Locale.ROOT).contains("on update")) {
            jdbc.execute("ALTER TABLE resource MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)");
        }
    }

    private boolean hasColumn() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resource' AND COLUMN_NAME='updated_at'",
                Integer.class);
        return count != null && count > 0;
    }
}
