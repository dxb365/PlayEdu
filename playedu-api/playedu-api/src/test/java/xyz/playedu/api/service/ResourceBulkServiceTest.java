package xyz.playedu.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.playedu.api.request.backend.ResourceBulkRequest;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.config.PlayEduConfig;
import xyz.playedu.common.context.BCtx;
import xyz.playedu.common.domain.Category;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.AppConfigService;
import xyz.playedu.common.service.CategoryService;
import xyz.playedu.common.util.S3Util;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.domain.ResourceExtra;
import xyz.playedu.resource.service.ResourceCategoryService;
import xyz.playedu.resource.service.ResourceExtraService;
import xyz.playedu.resource.service.ResourceService;

class ResourceBulkServiceTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    ResourceBulkService service;
    ResourceService resources;
    ResourceExtraService extras;
    ResourceCategoryService relations;
    BackendBus backend;
    TransactionTemplate transaction;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE resource (id INT PRIMARY KEY, admin_id INT, type VARCHAR(20), name VARCHAR(255), extension VARCHAR(20), size BIGINT, disk VARCHAR(20), path VARCHAR(255), parent_id INT, is_hidden INT, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE resource_extra (rid INT PRIMARY KEY, duration INT, poster INT)");
        jdbc.execute("CREATE TABLE resource_category (rid INT, cid INT)");
        jdbc.execute("CREATE TABLE courses (id INT PRIMARY KEY, thumb INT)");
        jdbc.execute("CREATE TABLE course_hour (course_id INT, rid INT)");
        jdbc.execute("CREATE TABLE course_attachment (course_id INT, rid INT)");
        resources = mock(ResourceService.class);
        extras = mock(ResourceExtraService.class);
        relations = mock(ResourceCategoryService.class);
        backend = mock(BackendBus.class);
        CategoryService categories = mock(CategoryService.class);
        when(categories.getById(1)).thenReturn(new Category());
        when(categories.getById(2)).thenReturn(new Category());
        when(backend.isSuperAdmin()).thenReturn(true);
        when(resources.chunks(anyList())).thenAnswer(call -> {
            List<Integer> ids = call.getArgument(0);
            return jdbc.query("SELECT * FROM resource WHERE id IN (" + placeholders(ids) + ")",
                    (row, index) -> resource(row), ids.toArray());
        });
        when(extras.chunksByRids(anyList())).thenAnswer(call -> {
            List<Integer> ids = call.getArgument(0);
            return jdbc.query("SELECT * FROM resource_extra WHERE rid IN (" + placeholders(ids) + ")", (row, index) -> {
                ResourceExtra extra = new ResourceExtra();
                extra.setRid(row.getInt("rid"));
                extra.setDuration(row.getInt("duration"));
                extra.setPoster(row.getInt("poster"));
                return extra;
            }, ids.toArray());
        });
        AtomicInteger nextId = new AtomicInteger(1000);
        when(resources.create(anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyInt(), anyInt())).thenAnswer(call -> {
                    int id = nextId.getAndIncrement();
                    jdbc.update("INSERT INTO resource (id,admin_id,type,name,extension,size,disk,path,parent_id,is_hidden) VALUES (?,?,?,?,?,?,?,?,?,?)", id,
                            call.getArgument(0), call.getArgument(2), call.getArgument(3),
                            call.getArgument(4), call.getArgument(5), call.getArgument(6),
                            call.getArgument(7), call.getArgument(8), call.getArgument(9));
                    String targets = call.getArgument(1);
                    if (!targets.isEmpty()) {
                        for (String category : targets.split(",")) {
                            jdbc.update("INSERT INTO resource_category VALUES (?,?)", id, Integer.valueOf(category));
                        }
                    }
                    Resource copy = new Resource();
                    copy.setId(id);
                    return copy;
                });
        doAnswer(call -> {
            jdbc.update("INSERT INTO resource_extra VALUES (?,?,?)",
                    call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
        }).when(extras).create(anyInt(), anyInt(), anyInt());
        doAnswer(call -> {
            jdbc.update("DELETE FROM resource_extra WHERE rid=?", call.getArgument(0, Integer.class));
            return null;
        }).when(extras).removeByRid(anyInt());
        doAnswer(call -> {
            int id = call.getArgument(0);
            List<Integer> ids = call.getArgument(1);
            jdbc.update("DELETE FROM resource_category WHERE rid=?", id);
            for (int category : ids) jdbc.update("INSERT INTO resource_category VALUES (?,?)", id, category);
            return null;
        }).when(relations).rebuild(anyInt(), anyList());
        when(resources.removeByIds(anyCollection())).thenAnswer(call -> {
            Collection<Integer> selectedIds = call.getArgument(0);
            List<Integer> ids = new ArrayList<>(selectedIds);
            return jdbc.update("DELETE FROM resource WHERE id IN (" + placeholders(ids) + ")", ids.toArray()) > 0;
        });
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        service = new ResourceBulkService(resources, extras, relations, categories, backend,
                mock(AppConfigService.class), mock(PlayEduConfig.class), jdbc, manager);
        BCtx.setId(7);
    }

    @AfterEach void clearContext() { BCtx.remove(); }

    @Test void movingAndChangingCategoriesPreservesResourceIdentity() {
        addVideo(1, 7, "nas", "lesson.mp4");
        perform("categories", List.of(1), List.of(1, 2));
        assertEquals(List.of(1, 2), categoryIds(1));
        perform("move", List.of(1), List.of(2));
        assertEquals(List.of(2), categoryIds(1));
        assertEquals("原视频", jdbc.queryForObject("SELECT name FROM resource WHERE id=1", String.class));
        assertEquals(12, jdbc.queryForObject("SELECT duration FROM resource_extra WHERE rid=1", Integer.class));
        verify(resources, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void nasCopyAndDeletionNeverWriteOrDeleteTheOriginalFile() throws Exception {
        Path original = Files.writeString(directory.resolve("lesson.mp4"), "original-video");
        addVideo(1, 7, "nas", "lesson.mp4");
        Map<String, Object> copied = perform("copy", List.of(1), List.of(2));
        assertEquals(List.of(1000), copied.get("created_ids"));
        assertEquals(List.of(2), categoryIds(1000));
        assertEquals("lesson.mp4", jdbc.queryForObject("SELECT path FROM resource WHERE id=1000", String.class));
        assertEquals("原视频（副本）", jdbc.queryForObject("SELECT name FROM resource WHERE id=1000", String.class));
        assertEquals(12, jdbc.queryForObject("SELECT duration FROM resource_extra WHERE rid=1000", Integer.class));
        try (var s3 = mockConstruction(S3Util.class)) {
            transaction.executeWithoutResult(status -> service.delete(List.of(1, 1000)));
            assertTrue(s3.constructed().isEmpty());
        }
        assertEquals("original-video", Files.readString(original));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class));
    }

    @Test void deletingAnUploadReferenceKeepsSharedMediaUntilTheLastReferenceIsRemoved() {
        addVideo(1, 7, "s3", "shared.mp4");
        perform("copy", List.of(1), List.of());
        try (var s3 = mockConstruction(S3Util.class)) {
            transaction.executeWithoutResult(status -> service.delete(List.of(1)));
            assertTrue(s3.constructed().isEmpty());
            transaction.executeWithoutResult(status -> service.delete(List.of(1000)));
            assertEquals(1, s3.constructed().size());
            verify(s3.constructed().get(0)).removeByPath("shared.mp4");
        }
    }

    @Test void mixedOwnershipIsRejectedBeforeAnyDeletion() {
        addVideo(1, 7, "nas", "a.mp4");
        addVideo(2, 8, "nas", "b.mp4");
        when(backend.isSuperAdmin()).thenReturn(false);
        assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(status -> service.delete(List.of(1, 2))));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class));
        verify(extras, never()).removeByRid(anyInt());
    }

    @Test void activeCourseReferenceRejectsTheEntireBatchAndDeletedCoursesDoNotBlockCleanup() {
        addVideo(1, 7, "nas", "a.mp4");
        addVideo(2, 7, "nas", "b.mp4");
        jdbc.update("INSERT INTO courses VALUES (10,0)");
        jdbc.update("INSERT INTO course_hour VALUES (10,1)");
        assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(status -> service.delete(List.of(1, 2))));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class));
        jdbc.update("DELETE FROM courses WHERE id=10");
        transaction.executeWithoutResult(status -> service.delete(List.of(1, 2)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class));
    }

    @Test void failureHalfwayThroughChangingCategoriesRollsBackEarlierChanges() {
        addVideo(1, 7, "nas", "a.mp4");
        addVideo(2, 7, "nas", "b.mp4");
        jdbc.update("INSERT INTO resource_category VALUES (1,1)");
        doThrow(new IllegalStateException("fixture failure")).when(relations).rebuild(eq(2), anyList());
        assertThrows(IllegalStateException.class, () -> perform("categories", List.of(1, 2), List.of(2)));
        assertEquals(List.of(1), categoryIds(1));
    }

    @Test void twoHundredVideosAreAcceptedAndTwoHundredOneAreRejected() {
        List<Integer> ids = IntStream.rangeClosed(1, 200).boxed().toList();
        for (int id : ids) addVideo(id, 7, "nas", id + ".mp4");
        assertEquals(200, perform("categories", ids, List.of()).get("affected"));
        List<Integer> tooMany = IntStream.rangeClosed(1, 201).boxed().toList();
        assertThrows(ServiceException.class, () -> perform("copy", tooMany, List.of(1)));
        assertThrows(ServiceException.class, () -> service.delete(tooMany));
        assertEquals(200, jdbc.queryForObject("SELECT COUNT(*) FROM resource", Integer.class));
    }

    private Map<String, Object> perform(String operation, List<Integer> ids, List<Integer> categoryIds) {
        return transaction.execute(status -> service.perform(new ResourceBulkRequest(operation, ids, categoryIds)));
    }

    private void addVideo(int id, int admin, String disk, String path) {
        jdbc.update("INSERT INTO resource (id,admin_id,type,name,extension,size,disk,path,parent_id,is_hidden) VALUES (?,?, 'VIDEO', '原视频', 'mp4', 12, ?, ?, 0, 0)", id, admin, disk, path);
        jdbc.update("INSERT INTO resource_extra VALUES (?,12,0)", id);
    }

    private List<Integer> categoryIds(int id) {
        return jdbc.queryForList("SELECT cid FROM resource_category WHERE rid=? ORDER BY cid", Integer.class, id);
    }

    private static String placeholders(List<Integer> ids) {
        return ids.stream().map(id -> "?").collect(Collectors.joining(","));
    }

    private static Resource resource(ResultSet row) throws SQLException {
        Resource resource = new Resource();
        resource.setId(row.getInt("id"));
        resource.setAdminId(row.getInt("admin_id"));
        resource.setType(row.getString("type"));
        resource.setName(row.getString("name"));
        resource.setExtension(row.getString("extension"));
        resource.setSize(row.getLong("size"));
        resource.setDisk(row.getString("disk"));
        resource.setPath(row.getString("path"));
        return resource;
    }
}
