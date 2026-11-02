package xyz.playedu.api.controller.frontend;

import java.io.IOException;
import java.util.Locale;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import xyz.playedu.resource.media.NasVideoStorage;
import xyz.playedu.resource.service.ResourceService;

@RestController
@RequestMapping("/nas-media")
public class NasMediaController {
    private final NasVideoStorage storage;
    private final ResourceService resources;

    public NasMediaController(NasVideoStorage storage, ResourceService resources) {
        this.storage = storage;
        this.resources = resources;
    }

    @RequestMapping(value = "/{id}/file", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> file(
            @PathVariable int id, @RequestParam long expires,
            @RequestParam String kind, @RequestParam String signature,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletRequest request) {
        if (!storage.verify(id, kind, expires, signature)
                || !("original".equals(kind) || "download".equals(kind))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        xyz.playedu.resource.domain.Resource entry = resources.getById(id);
        if (entry == null || !NasVideoStorage.isNasDisk(entry.getDisk())) {
            return ResponseEntity.notFound().build();
        }
        try {
            Resource source = storage.resource(entry.getPath());
            long size = source.contentLength();
            if (range != null && (range.length() > 128 || range.contains(","))) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size).build();
            }
            if (range != null && !"HEAD".equals(request.getMethod())) {
                try {
                    if (HttpRange.parseRanges(range).isEmpty()) throw new IllegalArgumentException();
                    HttpRange.toResourceRegions(HttpRange.parseRanges(range), source);
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size).build();
                }
            }
            String extension = entry.getExtension().toLowerCase(Locale.ROOT);
            String contentType = switch (extension) {
                case "mp4", "m4v" -> "video/mp4";
                case "webm" -> "video/webm";
                case "mkv" -> "video/x-matroska";
                case "mov" -> "video/quicktime";
                default -> "application/octet-stream";
            };
            ContentDisposition disposition = ("download".equals(kind)
                    ? ContentDisposition.attachment() : ContentDisposition.inline())
                    .filename(source.getFilename(), java.nio.charset.StandardCharsets.UTF_8).build();
            ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(size)
                    .lastModified(source.lastModified())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.noStore());
            return "HEAD".equals(request.getMethod()) ? response.build() : response.body(source);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
