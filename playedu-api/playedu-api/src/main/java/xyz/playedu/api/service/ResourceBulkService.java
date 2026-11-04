package xyz.playedu.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.playedu.api.request.backend.ResourceBulkRequest;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.config.PlayEduConfig;
import xyz.playedu.common.constant.BackendConstant;
import xyz.playedu.common.context.BCtx;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.AppConfigService;
import xyz.playedu.common.service.CategoryService;
import xyz.playedu.common.util.S3Util;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.domain.ResourceExtra;
import xyz.playedu.resource.media.NasVideoStorage;
import xyz.playedu.resource.service.ResourceCategoryService;
import xyz.playedu.resource.service.ResourceExtraService;
import xyz.playedu.resource.service.ResourceService;

@Service
@Slf4j
public class ResourceBulkService {
    private final ResourceService resources;
    private final ResourceExtraService extras;
    private final ResourceCategoryService relations;
    private final CategoryService categories;
    private final BackendBus backend;
    private final AppConfigService config;
    private final PlayEduConfig playEdu;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate cleanupTransaction;

    public ResourceBulkService(ResourceService resources, ResourceExtraService extras,
            ResourceCategoryService relations, CategoryService categories, BackendBus backend,
            AppConfigService config, PlayEduConfig playEdu, JdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this.resources = resources;
        this.extras = extras;
        this.relations = relations;
        this.categories = categories;
        this.backend = backend;
        this.config = config;
        this.playEdu = playEdu;
        this.jdbc = jdbc;
        cleanupTransaction = new TransactionTemplate(transactions);
        cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Map<String, Object> perform(ResourceBulkRequest request) {
        if (request.operation() == null || !Set.of("move", "copy", "categories").contains(request.operation())) {
            throw new ServiceException("批量操作类型不支持");
        }
        List<Integer> ids = validateIds(request.ids());
        List<Integer> categoryIds = validateCategories(request.categoryIds());
        if ("move".equals(request.operation()) && categoryIds.size() > 1) {
            throw new ServiceException("移动时请选择一个目标分类");
        }
        List<Resource> selected = selectedResources(ids);
        if (selected.size() != ids.size()) throw new ServiceException("部分视频已不存在，请刷新后重试");
        if (selected.stream().anyMatch(resource -> !BackendConstant.RESOURCE_TYPE_VIDEO.equals(resource.getType()))) {
            throw new ServiceException("批量操作只支持视频资源");
        }
        List<Integer> createdIds = new ArrayList<>();
        if ("copy".equals(request.operation())) {
            Map<Integer, ResourceExtra> metadata = extras.chunksByRids(ids).stream()
                    .collect(Collectors.toMap(ResourceExtra::getRid, value -> value));
            if (selected.stream().anyMatch(resource -> !metadata.containsKey(resource.getId()))) {
                throw new ServiceException("部分视频缺少时长信息，请先修复资源信息");
            }
            String targets = categoryIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            for (Resource source : selected) {
                Resource copy = resources.create(BCtx.getId(), targets, source.getType(),
                        copyName(source.getName()), source.getExtension(), source.getSize(),
                        source.getDisk(), source.getPath(), 0, 0);
                ResourceExtra sourceExtra = metadata.get(source.getId());
                extras.create(copy.getId(), sourceExtra.getDuration(), sourceExtra.getPoster());
                createdIds.add(copy.getId());
            }
        } else {
            for (Resource resource : selected) {
                relations.rebuild(resource.getId(), categoryIds);
                jdbc.update("UPDATE resource SET updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", resource.getId());
            }
        }
        return Map.of("affected", selected.size(), "created_ids", createdIds);
    }

    @Transactional
    public void delete(List<Integer> requestedIds) {
        List<Integer> ids = validateIds(requestedIds);
        List<Resource> selected = selectedResources(ids);
        if (selected.isEmpty()) return;
        List<Integer> existingIds = selected.stream().map(Resource::getId).toList();
        requireUnused(existingIds);
        if (Boolean.TRUE.equals(playEdu.getDemoEnabled())) {
            throw new ServiceException("演示环境不支持删除资源");
        }
        for (Resource resource : selected) {
            if (BackendConstant.RESOURCE_TYPE_VIDEO.equals(resource.getType())) {
                extras.removeByRid(resource.getId());
            }
        }
        relations.remove(new QueryWrapper<xyz.playedu.resource.domain.ResourceCategory>().in("rid", existingIds));
        if (!resources.removeByIds(existingIds)) throw new ServiceException("资源删除失败，请重试");
        List<MediaObject> media = selected.stream()
                .filter(resource -> !NasVideoStorage.isNasDisk(resource.getDisk()))
                .map(resource -> new MediaObject(resource.getDisk(), resource.getPath()))
                .distinct().toList();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup(media); }
            });
        } else {
            cleanup(media);
        }
    }

    private List<Integer> validateIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 200
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ServiceException("每次请选择 1 至 200 个资源");
        }
        return ids.stream().distinct().sorted().toList();
    }

    private List<Integer> validateCategories(List<Integer> ids) {
        if (ids == null || ids.size() > 50
                || ids.stream().anyMatch(id -> id == null || id < 0)) {
            throw new ServiceException("分类参数错误");
        }
        if (ids.contains(0) && ids.size() > 1) throw new ServiceException("未分类不能与其他分类同时选择");
        List<Integer> selected = ids.stream().filter(id -> id > 0).distinct().toList();
        for (Integer id : selected) {
            if (categories.getById(id) == null) throw new ServiceException("目标分类不存在");
        }
        return selected;
    }

    private List<Resource> selectedResources(List<Integer> ids) {
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        jdbc.queryForList("SELECT id FROM resource WHERE id IN (" + placeholders
                + ") ORDER BY id FOR UPDATE", ids.toArray());
        List<Resource> selected = resources.chunks(ids).stream()
                .sorted(Comparator.comparing(Resource::getId)).toList();
        if (!backend.isSuperAdmin()
                && selected.stream().anyMatch(resource -> !Objects.equals(resource.getAdminId(), BCtx.getId()))) {
            throw new ServiceException("部分资源无操作权限");
        }
        return selected;
    }

    private void requireUnused(List<Integer> ids) {
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT (SELECT COUNT(*) FROM course_hour h JOIN courses c ON c.id = h.course_id WHERE h.rid IN (" + placeholders
                + ")) + (SELECT COUNT(*) FROM course_attachment a JOIN courses c ON c.id = a.course_id WHERE a.rid IN (" + placeholders
                + ")) + (SELECT COUNT(*) FROM courses WHERE thumb IN (" + placeholders + "))";
        List<Integer> values = new ArrayList<>(ids);
        values.addAll(ids);
        values.addAll(ids);
        Long references = jdbc.queryForObject(sql, Long.class, values.toArray());
        if (references == null || references > 0) {
            throw new ServiceException("部分资源正在被课程使用，请先移除课程中的引用");
        }
    }

    private void cleanup(List<MediaObject> media) {
        for (MediaObject object : media) {
            try {
                cleanupTransaction.executeWithoutResult(status -> {
                    List<Map<String, Object>> remaining = jdbc.queryForList(
                            "SELECT id FROM resource WHERE disk = ? AND path = ? FOR UPDATE",
                            object.disk(), object.path());
                    if (remaining.isEmpty()) {
                        new S3Util(config.getS3Config()).removeByPath(object.path());
                    }
                });
            } catch (RuntimeException error) {
                log.warn("Resource metadata removed; media cleanup requires retry", error);
            }
        }
    }

    private static String copyName(String name) {
        int characters = name.codePointCount(0, name.length());
        String base = characters > 250 ? name.substring(0, name.offsetByCodePoints(0, 250)) : name;
        return base + "（副本）";
    }

    private record MediaObject(String disk, String path) {}
}
