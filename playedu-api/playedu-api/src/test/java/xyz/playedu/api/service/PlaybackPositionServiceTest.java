package xyz.playedu.api.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Teaching customization: test real SQL persistence, user isolation and delayed requests. */
class PlaybackPositionServiceTest {
    private PlaybackPositionService positions;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE teaching_playback_positions (user_id INT,course_id INT,hour_id INT,position INT,client_id VARCHAR(64),sequence_no BIGINT,updated_at TIMESTAMP(6),PRIMARY KEY(user_id,course_id,hour_id))");
        positions=new PlaybackPositionService(jdbc);
    }
    @Test void rewindingSavesTheCurrentCursor() {
        positions.save(1,10,20,180,"player",1);
        positions.save(1,10,20,32,"player",2);
        assertEquals(32, ((Number) positions.find(1,10,20).get("position")).intValue());
    }
    @Test void olderInflightSaveCannotOverwriteThePausePosition() {
        positions.save(1,10,20,32,"player",3);
        positions.save(1,10,20,180,"player",2);
        assertEquals(32, ((Number) positions.find(1,10,20).get("position")).intValue());
    }
    @Test void usersHaveIndependentPositions() {
        positions.save(1,10,20,50,"a",1);
        positions.save(2,10,20,90,"b",1);
        assertEquals(50, ((Number) positions.find(1,10,20).get("position")).intValue());
        assertEquals(90, ((Number) positions.find(2,10,20).get("position")).intValue());
        assertNull(positions.find(3,10,20));
        assertTrue(positions.latest(3).isEmpty());
    }
    @Test void reopeningAPlayerCanStartANewSequence() {
        positions.save(1,10,20,50,"old",100);
        positions.save(1,10,20,55,"new",1);
        assertEquals(55, ((Number) positions.find(1,10,20).get("position")).intValue());
    }
}
