package xyz.playedu.resource.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class NasVideoProbe {
    private final NasVideoStorage storage;
    private final ObjectMapper mapper;
    private final String executable;
    private final Map<String, ProbeInfo> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, ProbeInfo>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, ProbeInfo> entry) {
                    return size() > 128;
                }
            });
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService readers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "nas-video-probe");
        thread.setDaemon(true);
        return thread;
    });

    public NasVideoProbe(NasVideoStorage storage, ObjectMapper mapper, Environment environment) {
        this.storage = storage;
        this.mapper = mapper;
        executable = environment.getProperty("TEACHING_NAS_FFPROBE", "/usr/bin/ffprobe");
    }

    public ProbeInfo inspect(String relative) throws IOException {
        Path source = storage.resolve(relative, false);
        long size = Files.size(source);
        long modified = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis();
        String fingerprint = source + "\u0000" + size + "\u0000" + modified;
        ProbeInfo known = cache.get(fingerprint);
        if (known != null) return known;
        Process process = null;
        Future<byte[]> output = null;
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) throw new IOException("视频信息检查繁忙，请稍后重试");
            known = cache.get(fingerprint);
            if (known != null) return known;
            List<String> command = new ArrayList<>(List.of(executable, "-v", "error",
                    "-protocol_whitelist", "file,pipe"));
            command.addAll(inputArguments(source));
            command.addAll(List.of("-show_entries",
                    "format=duration,format_name:stream=codec_type,codec_name,duration:stream_disposition=attached_pic",
                    "-of", "json", source.toString()));
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            final Process running = process;
            output = readers.submit(() -> readBounded(running.getInputStream()));
            byte[] json = output.get(20, TimeUnit.SECONDS);
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("无法读取视频信息，请检查格式或读取权限");
            }
            if (size != Files.size(source)
                    || modified != Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis()) {
                throw new IOException("视频正在变化，请在文件写入完成后重试");
            }
            ProbeInfo info = parse(json, size, modified);
            cache.put(fingerprint, info);
            return info;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("视频信息检查已取消");
        } catch (TimeoutException e) {
            throw new IOException("读取视频信息超时");
        } catch (ExecutionException e) {
            throw new IOException("无法读取视频信息");
        } finally {
            if (output != null) output.cancel(true);
            if (process != null) process.destroyForcibly();
            if (acquired) slots.release();
        }
    }

    ProbeInfo parse(byte[] json, long size, long modified) throws IOException {
        JsonNode data = mapper.readTree(json);
        if (data == null) throw new IOException("视频信息为空");
        String video = "";
        String audio = "";
        double streamDuration = 0;
        for (JsonNode stream : data.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText()) && video.isEmpty()
                    && stream.path("disposition").path("attached_pic").asInt(0) == 0) {
                video = stream.path("codec_name").asText();
            } else if ("audio".equals(stream.path("codec_type").asText()) && audio.isEmpty()) {
                audio = stream.path("codec_name").asText();
            }
            streamDuration = Math.max(streamDuration, stream.path("duration").asDouble(0));
        }
        double duration = data.path("format").path("duration").asDouble(streamDuration);
        if (video.isEmpty() || !Double.isFinite(duration) || duration <= 0
                || duration > 604_800 || size <= 0) {
            throw new IOException("文件没有可用的视频轨道或时长");
        }
        return new ProbeInfo(duration, video, audio,
                data.path("format").path("format_name").asText(), size, modified);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (result.size() + count > 131_072) throw new IOException("Video metadata too large");
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }

    static List<String> inputArguments(Path source) throws IOException {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String format = switch (extension) {
            case "mp4", "m4v", "mov" -> "mov";
            case "mkv", "webm" -> "matroska";
            case "avi" -> "avi";
            case "wmv" -> "asf";
            case "flv" -> "flv";
            case "ts" -> "mpegts";
            case "mpeg", "mpg" -> "mpeg";
            default -> throw new IOException("不支持此视频容器");
        };
        return "mov".equals(format)
                ? List.of("-f", format, "-enable_drefs", "0", "-use_absolute_path", "0")
                : List.of("-f", format);
    }

    @PreDestroy
    public void close() {
        readers.shutdownNow();
    }

    public record ProbeInfo(double duration, String videoCodec, String audioCodec,
                            String container, long size, long modified) {
        public boolean directlyPlayable(String extension) {
            String ext = extension.toLowerCase(Locale.ROOT);
            if (Set.of("mp4", "m4v").contains(ext)) {
                return "h264".equals(videoCodec)
                        && Set.of("", "aac", "mp3").contains(audioCodec);
            }
            return "webm".equals(ext) && Set.of("vp8", "vp9", "av1").contains(videoCodec)
                    && Set.of("", "opus", "vorbis").contains(audioCodec);
        }
    }
}
