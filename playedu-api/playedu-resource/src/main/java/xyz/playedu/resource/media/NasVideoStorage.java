package xyz.playedu.resource.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.AbstractResource;
import org.springframework.stereotype.Component;

@Component
public class NasVideoStorage {
    public static final String ORIGINAL_DISK = "nas";
    public static final long LINK_SECONDS = 43_200;
    private final Path root;
    private final String publicUrl;
    private final byte[] key;
    private final String adminOrigin;
    private final Clock clock;

    @Autowired
    public NasVideoStorage(Environment environment) {
        this(environment, Clock.systemUTC());
    }

    NasVideoStorage(Environment environment, Clock clock) {
        this.clock = clock;
        String configuredRoot = environment.getProperty("TEACHING_NAS_VIDEO_ROOT", "").trim();
        root = configuredRoot.isEmpty() ? null : Path.of(configuredRoot).toAbsolutePath().normalize();
        publicUrl = environment.getProperty("TEACHING_NAS_PUBLIC_URL", "").replaceAll("/+$", "");
        String secret = environment.getProperty("sa-token.jwt-secret-key", "");
        key = secret.getBytes(StandardCharsets.UTF_8);
        adminOrigin = environment.getProperty("TEACHING_NAS_ADMIN_ORIGIN", "").trim();
        if (root != null) {
            URI uri = URI.create(publicUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || key.length < 32) {
                throw new IllegalArgumentException("Invalid NAS video URL or signing key");
            }
        }
    }

    public static boolean isNasDisk(String disk) {
        return ORIGINAL_DISK.equals(disk);
    }

    public boolean isEnabled() {
        return root != null;
    }

    public String[] allowedOrigins() {
        if (!isEnabled()) return new String[0];
        URI uri = URI.create(publicUrl);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        return adminOrigin.isEmpty() ? new String[] {origin} : new String[] {origin, adminOrigin};
    }

    public Path resolve(String relative, boolean directory) throws IOException {
        Path parsed = parse(relative);
        Path realRoot = rootPath();
        Path current = realRoot;
        for (Path component : parsed) {
            if (component.toString().isEmpty()) continue;
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are unavailable");
        }
        Path resolved = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!resolved.startsWith(realRoot)
                || (directory ? !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)
                              : !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Video library entry is unavailable");
        }
        return resolved;
    }

    public String relative(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path base = rootPath();
        if (!normalized.startsWith(base)) throw new IOException("Entry outside video library");
        return base.relativize(normalized).toString().replace('\\', '/');
    }

    private Path parse(String relative) throws IOException {
        if (relative != null && relative.length() > 255) {
            throw new IOException("视频相对路径超过 255 个字符，请选择较浅的目录作为视频库根目录");
        }
        if (root == null || relative == null
                || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IOException("Invalid video library path");
        }
        Path parsed;
        try {
            parsed = Path.of(relative);
        } catch (InvalidPathException e) {
            throw new IOException("Invalid video library path");
        }
        if (parsed.isAbsolute()) throw new IOException("Absolute paths are unavailable");
        for (Path component : parsed) {
            if ("..".equals(component.toString()) || ".".equals(component.toString())) {
                throw new IOException("Invalid video library path");
            }
        }
        return parsed;
    }

    private Path rootPath() throws IOException {
        if (root == null || Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("NAS video library is not mounted");
        }
        return root.toRealPath();
    }

    public SeekableByteChannel open(String relative) throws IOException {
        Path parsed = parse(relative);
        if (relative.isEmpty() || parsed.getNameCount() == 0) throw new IOException("Missing video");
        DirectoryStream<Path> directory = Files.newDirectoryStream(rootPath());
        try {
            if (!(directory instanceof SecureDirectoryStream<?>)) {
                throw new IOException("Secure video file access requires Linux");
            }
            for (int index = 0; index < parsed.getNameCount() - 1; index++) {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> secured = (SecureDirectoryStream<Path>) directory;
                DirectoryStream<Path> next =
                        secured.newDirectoryStream(parsed.getName(index), LinkOption.NOFOLLOW_LINKS);
                directory.close();
                directory = next;
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secured = (SecureDirectoryStream<Path>) directory;
            return secured.newByteChannel(
                    parsed.getFileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } finally {
            directory.close();
        }
    }

    public org.springframework.core.io.Resource resource(String relative) throws IOException {
        Path source = resolve(relative, false);
        long length = Files.size(source);
        long modified = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis();
        String filename = source.getFileName().toString();
        return new AbstractResource() {
            @Override public String getDescription() { return "NAS video"; }
            @Override public String getFilename() { return filename; }
            @Override public long contentLength() { return length; }
            @Override public long lastModified() { return modified; }
            @Override public InputStream getInputStream() throws IOException {
                return Channels.newInputStream(open(relative));
            }
        };
    }

    public String signedUrl(int id, String disk, boolean download) {
        if (!isEnabled() || id <= 0 || !isNasDisk(disk)) return "";
        String kind = download ? "download" : "original";
        long expires = clock.instant().getEpochSecond() + LINK_SECONDS;
        String suffix = "/file";
        return publicUrl + "/" + id + suffix + "?expires=" + expires
                + "&kind=" + kind + "&signature=" + sign(id, kind, expires);
    }

    public boolean verify(int id, String kind, long expires, String signature) {
        long now = clock.instant().getEpochSecond();
        if (!isEnabled() || id <= 0 || kind == null || signature == null || signature.length() != 64
                || expires <= now || expires > now + LINK_SECONDS + 30
                || !Set.of("original", "download").contains(kind)) return false;
        return MessageDigest.isEqual(sign(id, kind, expires).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(int id, String kind, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    ("nas-video-v1\n" + id + "\n" + kind + "\n" + expires)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Video signing unavailable", e);
        }
    }
}
