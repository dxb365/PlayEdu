package xyz.playedu.api.controller.backend;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import xyz.playedu.common.bus.BackendBus;
import xyz.playedu.common.constant.BackendConstant;
import xyz.playedu.common.context.BCtx;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.service.CategoryService;
import xyz.playedu.common.types.JsonResponse;
import xyz.playedu.resource.domain.Resource;
import xyz.playedu.resource.media.NasVideoProbe;
import xyz.playedu.resource.media.NasVideoStorage;
import xyz.playedu.resource.service.ResourceExtraService;
import xyz.playedu.resource.service.ResourceService;

@RestController
@RequestMapping("/backend/v1/nas-video")
public class NasVideoController {
    private static final Set<String> EXTENSIONS =
            Set.of("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "ts", "mpeg", "mpg");
    private final NasVideoStorage storage;
    private final NasVideoProbe probe;
    private final BackendBus backend;
    private final ResourceService resources;
    private final ResourceExtraService extras;
    private final CategoryService categories;
    private final TransactionTemplate transaction;
    private final Object importLock = new Object();

    public NasVideoController(NasVideoStorage storage, NasVideoProbe probe, BackendBus backend,
            ResourceService resources, ResourceExtraService extras, CategoryService categories,
            PlatformTransactionManager transactions) {
        this.storage = storage;
        this.probe = probe;
        this.backend = backend;
        this.resources = resources;
        this.extras = extras;
        this.categories = categories;
        transaction = new TransactionTemplate(transactions);
    }

    @GetMapping("/status")
    public JsonResponse status() {
        boolean enabled = storage.isEnabled() && backend.isSuperAdmin();
        boolean mounted = false;
        if (enabled) {
            try {
                storage.resolve("", true);
                mounted = true;
            } catch (IOException ignored) {
            }
        }
        return JsonResponse.data(Map.of("enabled", enabled, "mounted", mounted));
    }

    @GetMapping("/browse")
    public JsonResponse browse(@RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireAdmin();
        try {
            Path directory = storage.resolve(path, true);
            List<Entry> all = new ArrayList<>();
            boolean truncated = false;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    if (Files.isSymbolicLink(child)) continue;
                    String filename = child.getFileName().toString();
                    if (!filename.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    boolean folder = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                    String ext = extension(filename);
                    if (!folder && (!EXTENSIONS.contains(ext)
                            || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))) continue;
                    try {
                        all.add(new Entry(filename, storage.relative(child), folder,
                                folder ? 0 : Files.size(child), ext, false));
                    } catch (IOException ignored) {
                        continue;
                    }
                    if (all.size() > 20_000) {
                        all.remove(all.size() - 1);
                        truncated = true;
                        break;
                    }
                }
            }
            all.sort(Comparator.comparing(Entry::directory).reversed()
                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Entry::name));
            int limit = Math.min(200, Math.max(1, size));
            int offset = (int) Math.min(all.size(), Math.max(0L, (long) page - 1) * limit);
            List<Entry> entries = all.subList(offset, Math.min(all.size(), offset + limit));
            List<String> paths = entries.stream().filter(e -> !e.directory()).map(Entry::path).toList();
            Set<String> existing = paths.isEmpty() ? Set.of()
                    : resources.list(new QueryWrapper<Resource>()
                            .eq("disk", NasVideoStorage.ORIGINAL_DISK)
                            .in("path", paths)).stream().map(Resource::getPath).collect(Collectors.toSet());
            List<Entry> result = entries.stream().map(e -> new Entry(
                    e.name(), e.path(), e.directory(), e.size(), e.extension(),
                    existing.contains(e.path()))).toList();
            String current = storage.relative(directory);
            int slash = current.lastIndexOf('/');
            String parent = slash < 0 ? "" : current.substring(0, slash);
            return JsonResponse.data(Map.of("entries", result, "path", current, "parent", parent,
                    "total", all.size(), "truncated", truncated));
        } catch (IOException e) {
            throw new ServiceException("无法打开目录，请检查只读挂载和共享文件夹读取权限");
        }
    }

    @PostMapping("/import")
    public JsonResponse importVideo(@RequestBody @Valid ImportRequest request) {
        requireAdmin();
        List<Integer> categoryIds = request.categoryIds() == null ? List.of() : request.categoryIds();
        if (categoryIds.size() > 50) throw new ServiceException("分类数量过多");
        for (Integer id : categoryIds) {
            if (id == null || id < 0 || (id > 0 && categories.getById(id) == null)) {
                throw new ServiceException("分类不存在");
            }
        }
        try {
            Path source = storage.resolve(request.path(), false);
            String relative = storage.relative(source);
            String filename = source.getFileName().toString();
            String ext = extension(filename);
            if (!EXTENSIONS.contains(ext)) throw new ServiceException("暂不支持此文件扩展名");
            String title = filename.substring(0, filename.length() - ext.length() - 1);
            if (title.isBlank() || title.length() > 254) throw new ServiceException("视频名称过长或为空");
            NasVideoProbe.ProbeInfo info = probe.inspect(relative);
            String disk = NasVideoStorage.ORIGINAL_DISK;
            Map<String, Object> result;
            synchronized (importLock) {
                result = transaction.execute(status -> {
                    List<Resource> matches = resources.list(new QueryWrapper<Resource>()
                            .eq("disk", NasVideoStorage.ORIGINAL_DISK)
                            .eq("path", relative));
                    Resource existing = matches.stream()
                            .filter(value -> relative.equals(value.getPath())).findFirst().orElse(null);
                    if (existing != null) {
                        return new HashMap<>(Map.of("id", existing.getId(), "created", false,
                                "name", existing.getName(), "disk", existing.getDisk()));
                    }
                    String ids = categoryIds.stream().map(String::valueOf).collect(Collectors.joining(","));
                    Resource created = resources.create(BCtx.getId(), ids,
                            BackendConstant.RESOURCE_TYPE_VIDEO, title, ext, info.size(), disk,
                            relative, 0, 0);
                    extras.create(created.getId(), (int) Math.ceil(info.duration()), 0);
                    return new HashMap<>(Map.of("id", created.getId(), "created", true,
                            "name", title, "disk", disk));
                });
            }
            if (result == null) throw new ServiceException("视频引用未保存");
            result.put("duration", info.duration());
            result.put("video_codec", info.videoCodec());
            result.put("audio_codec", info.audioCodec());
            result.put("warning", !info.directlyPlayable(ext)
                    ? "原格式的兼容性取决于浏览器和设备，请先预览。" : "");
            return JsonResponse.data(result);
        } catch (IOException e) {
            throw new ServiceException(e.getMessage());
        }
    }

    private void requireAdmin() {
        if (!backend.isSuperAdmin()) throw new ServiceException("仅超级管理员可访问 NAS 视频库");
        if (!storage.isEnabled()) throw new ServiceException("尚未配置 NAS 视频目录");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record Entry(String name, String path, boolean directory, long size,
                        String extension, boolean imported) {}

    public record ImportRequest(
            @NotBlank @Size(max = 1_000) String path,
            @JsonProperty("category_ids") List<Integer> categoryIds) {}
}
