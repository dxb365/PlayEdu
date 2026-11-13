package xyz.playedu.resource.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import xyz.playedu.common.constant.BackendConstant;
import xyz.playedu.resource.domain.Resource;

@Component
public class NasSubtitleLibrary {
    private static final Set<String> TEXT_FORMATS = Set.of("vtt", "srt", "ass", "ssa");
    private static final int TEXT_LIMIT = 4 * 1024 * 1024;
    private static final int IDX_LIMIT = 1024 * 1024;
    private static final int SUB_LIMIT = 32 * 1024 * 1024;
    private static final int MAX_TRACKS = 32;
    private static final int MAX_FILES = 128;
    private static final int MAX_DIRECTORY_ENTRIES = 20_000;
    private static final Pattern IDX_STREAM = Pattern.compile(
            "id:\\s*([a-z]{2,3}(?:[-_][a-z0-9]{2,8})?),\\s*index:\\s*(\\d{1,2})",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> ISO_LANGUAGES = isoLanguages();
    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("chi", "zh"), Map.entry("chinese", "zh"),
            Map.entry("中文", "zh"), Map.entry("cn", "zh"),
            Map.entry("chs", "zh-Hans"), Map.entry("简体", "zh-Hans"),
            Map.entry("简中", "zh-Hans"), Map.entry("简体中文", "zh-Hans"),
            Map.entry("cht", "zh-Hant"), Map.entry("繁体", "zh-Hant"),
            Map.entry("繁中", "zh-Hant"), Map.entry("繁体中文", "zh-Hant"),
            Map.entry("english", "en"), Map.entry("英文", "en"), Map.entry("英语", "en"),
            Map.entry("japanese", "ja"), Map.entry("日文", "ja"), Map.entry("日语", "ja"),
            Map.entry("korean", "ko"), Map.entry("韩文", "ko"), Map.entry("韩语", "ko"),
            Map.entry("中英", "mul"), Map.entry("中英双语", "mul"),
            Map.entry("简英", "mul"), Map.entry("繁英", "mul"),
            Map.entry("zh-en", "mul"), Map.entry("en-zh", "mul"));

    private final NasVideoStorage storage;

    public NasSubtitleLibrary(NasVideoStorage storage) {
        this.storage = storage;
    }

    public List<Track> discover(Resource video) {
        if (!storage.isEnabled() || video == null || !NasVideoStorage.isNasDisk(video.getDisk())
                || !BackendConstant.RESOURCE_TYPE_VIDEO.equals(video.getType())) return List.of();

        try {
            Path source = storage.resolve(video.getPath(), false);
            String filename = source.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            if (dot < 1) return List.of();
            return findTracks(sidecars(source.getParent(), filename.substring(0, dot)));
        } catch (IOException | DirectoryIteratorException e) {
            return List.of();
        }
    }

    private List<Sidecar> sidecars(Path directory, String videoStem) throws IOException {
        List<Sidecar> matches = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            int visited = 0;
            for (Path entry : entries) {
                if (++visited > MAX_DIRECTORY_ENTRIES || matches.size() >= MAX_FILES) break;
                String filename = entry.getFileName().toString();
                int dot = filename.lastIndexOf('.');
                if (dot < 1) continue;
                String stem = filename.substring(0, dot);
                String format = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
                int maximum = fileLimit(format);
                if (maximum == 0) continue;

                String language = "und";
                if (!stem.equals(videoStem)) {
                    if (!stem.startsWith(videoStem + ".")) continue;
                    language = language(stem.substring(videoStem.length() + 1));
                    if (language == null) continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    String path = storage.relative(entry);
                    Path checked = storage.resolve(path, false);
                    long size = Files.size(checked);
                    if (size > 0 && size <= maximum) {
                        matches.add(new Sidecar(path, stem, format, language));
                    }
                } catch (IOException ignored) {
                }
            }
        }
        matches.sort(Comparator.comparing(Sidecar::path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Sidecar::path));
        return matches;
    }

    private List<Track> findTracks(List<Sidecar> files) {
        Map<String, List<Sidecar>> subFiles = new HashMap<>();
        for (Sidecar file : files) {
            if ("sub".equals(file.format())) {
                subFiles.computeIfAbsent(file.stem(),
                        ignored -> new ArrayList<>()).add(file);
            }
        }
        List<Track> tracks = new ArrayList<>();
        for (Sidecar file : files) {
            if (TEXT_FORMATS.contains(file.format())) {
                tracks.add(track(file, null, file.language(), null));
            } else if ("idx".equals(file.format())) {
                List<Sidecar> choices = subFiles.get(file.stem());
                if (choices != null && choices.size() == 1) {
                    for (Map.Entry<Integer, String> stream : readStreams(file).entrySet()) {
                        tracks.add(track(file, choices.get(0), stream.getValue(), stream.getKey()));
                    }
                }
            }
            if (tracks.size() >= MAX_TRACKS) break;
        }
        tracks.sort(Comparator.comparingInt((Track track) -> languageOrder(track.language()))
                .thenComparing(Track::label)
                .thenComparingInt(track -> formatOrder(track.format())));
        return tracks.stream().limit(MAX_TRACKS).toList();
    }

    private Map<Integer, String> readStreams(Sidecar index) {
        try (SubtitleFile file = open(index.path(), IDX_LIMIT)) {
            String data = new String(file.input().readNBytes((int) file.length()), StandardCharsets.ISO_8859_1);
            Map<Integer, String> streams = new LinkedHashMap<>();
            var lines = data.lines().iterator();
            while (lines.hasNext() && streams.size() < MAX_TRACKS) {
                String line = lines.next();
                if (line.length() > 160) continue;
                var matcher = IDX_STREAM.matcher(line.strip());
                if (!matcher.matches()) continue;
                int number = Integer.parseInt(matcher.group(2));
                if (number > 31) continue;
                String language = language(matcher.group(1));
                if (language == null || "und".equals(language)) language = index.language();
                streams.putIfAbsent(number, language);
            }
            return streams;
        } catch (IOException e) {
            return Map.of();
        }
    }

    public SubtitleFile open(Track track, boolean binary) throws IOException {
        if (binary) {
            if (track.dataPath() == null) throw new IOException("Subtitle data unavailable");
            return open(track.dataPath(), SUB_LIMIT);
        }
        return open(track.path(), "vobsub".equals(track.format()) ? IDX_LIMIT : TEXT_LIMIT);
    }

    private SubtitleFile open(String path, int maximum) throws IOException {
        storage.resolve(path, false);
        SeekableByteChannel channel = storage.open(path);
        try {
            long size = channel.size();
            if (size <= 0 || size > maximum) throw new IOException("Subtitle size unavailable");
            return new SubtitleFile(Channels.newInputStream(channel), size);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private static int fileLimit(String format) {
        if (TEXT_FORMATS.contains(format)) return TEXT_LIMIT;
        if ("idx".equals(format)) return IDX_LIMIT;
        if ("sub".equals(format)) return SUB_LIMIT;
        return 0;
    }

    private static Track track(Sidecar file, Sidecar binary, String language, Integer stream) {
        String format = binary == null ? file.format() : "vobsub";
        String id;
        try {
            id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (file.path() + "\n" + stream).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Subtitle identifier unavailable", e);
        }
        return new Track(id, label(language), language, format, file.path(),
                binary == null ? null : binary.path(), stream);
    }

    private static String language(String value) {
        String normalized = value.strip().replace('_', '-').toLowerCase(Locale.ROOT);
        String alias = LANGUAGE_ALIASES.get(normalized);
        if (alias != null) return alias;
        if (!normalized.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}")) return null;
        int dash = normalized.indexOf('-');
        String first = dash < 0 ? normalized : normalized.substring(0, dash);
        if ("und".equals(first) || "mul".equals(first)) return first;
        String primary = ISO_LANGUAGES.get(first);
        if (primary == null) return null;
        return Locale.forLanguageTag(primary + (dash < 0 ? "" : normalized.substring(dash))).toLanguageTag();
    }

    private static Map<String, String> isoLanguages() {
        Map<String, String> codes = new HashMap<>();
        for (String language : Locale.getISOLanguages()) {
            Locale locale = Locale.forLanguageTag(language);
            codes.put(language, locale.getLanguage());
            try {
                codes.put(locale.getISO3Language(), locale.getLanguage());
            } catch (MissingResourceException ignored) {
            }
        }
        return Map.copyOf(codes);
    }

    private static String label(String language) {
        return switch (language) {
            case "zh-Hans", "zh-CN", "zh-SG" -> "简体中文";
            case "zh-Hant", "zh-TW", "zh-HK", "zh-MO" -> "繁体中文";
            case "mul" -> "中英双语";
            case "und" -> "字幕";
            default -> switch (Locale.forLanguageTag(language).getLanguage()) {
                case "zh" -> "中文";
                case "en" -> "英文";
                case "ja" -> "日文";
                case "ko" -> "韩文";
                default -> Locale.forLanguageTag(language).getDisplayName(Locale.SIMPLIFIED_CHINESE);
            };
        };
    }

    private static int languageOrder(String language) {
        if (language.startsWith("zh")) return 0;
        if (language.startsWith("en")) return 1;
        return "und".equals(language) ? 3 : 2;
    }

    private static int formatOrder(String format) {
        return switch (format) {
            case "vtt" -> 0;
            case "srt" -> 1;
            case "ass", "ssa" -> 2;
            default -> 3;
        };
    }

    private record Sidecar(String path, String stem, String format, String language) {}

    public record Track(String id, String label, String language, String format,
                        String path, String dataPath, Integer streamIndex) {}

    public record SubtitleFile(InputStream input, long length) implements AutoCloseable {
        @Override public void close() throws IOException {
            input.close();
        }
    }
}
