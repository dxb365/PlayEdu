package xyz.playedu.resource.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import xyz.playedu.resource.domain.Resource;

class NasSubtitleLibraryTest {
    @TempDir Path directory;

    private NasSubtitleLibrary library() {
        return new NasSubtitleLibrary(new NasVideoStorage(new MockEnvironment()
                .withProperty("TEACHING_NAS_VIDEO_ROOT", directory.toString())
                .withProperty("TEACHING_NAS_PUBLIC_URL", "https://classroom.example:8443/api/nas-media")
                .withProperty("sa-token.jwt-secret-key", "isolated-nas-test-key-01234567890123456789")));
    }

    private static Resource video(String path) {
        Resource video = new Resource();
        video.setDisk("nas");
        video.setType("VIDEO");
        video.setPath(path);
        return video;
    }

    @Test void discoversOnlyMatchingTextTracksWithinTheVideoFolderAndSizeLimit() throws Exception {
        Files.createDirectory(directory.resolve("课程"));
        Files.writeString(directory.resolve("课程/第1集.mp4"), "video");
        Files.writeString(directory.resolve("课程/第1集.zh-CN.VTT"), "WEBVTT");
        Files.writeString(directory.resolve("课程/第1集.英文.srt"), "subtitle");
        Files.writeString(directory.resolve("课程/第1集.srt"), "subtitle");
        Files.writeString(directory.resolve("课程/第1集.ja.srt"), "");
        Files.writeString(directory.resolve("课程/第1集2.en.srt"), "subtitle");
        Files.writeString(directory.resolve("课程/第1集.另一集.en.srt"), "subtitle");
        Files.writeString(directory.resolve("课程/第1集.sub"), "unpaired");
        Files.writeString(directory.resolve("第1集.zh.srt"), "other folder");
        try (RandomAccessFile large = new RandomAccessFile(
                directory.resolve("课程/第1集.fr.srt").toFile(), "rw")) {
            large.setLength(4 * 1024 * 1024 + 1);
        }

        NasSubtitleLibrary library = library();
        var tracks = library.discover(video("课程/第1集.mp4"));
        assertEquals(List.of("zh-CN", "en", "und"), tracks.stream().map(
                NasSubtitleLibrary.Track::language).toList());
        assertEquals(List.of("简体中文", "英文", "字幕"), tracks.stream().map(
                NasSubtitleLibrary.Track::label).toList());
        assertEquals(List.of("vtt", "srt", "srt"), tracks.stream().map(
                NasSubtitleLibrary.Track::format).toList());
        assertTrue(library.discover(video("课程/未找到.mp4")).isEmpty());
        assertTrue(library.discover(video("../第1集.mp4")).isEmpty());
        Resource uploaded = video("课程/第1集.mp4");
        uploaded.setDisk("s3");
        assertTrue(library.discover(uploaded).isEmpty());
    }

    @Test @EnabledOnOs(OS.LINUX)
    void discoversMultipleStreamsInOneVobSubPairAndRejectsSymlinks() throws Exception {
        Files.writeString(directory.resolve("第一集.mp4"), "video");
        Files.writeString(directory.resolve("第一集.idx"), """
                # VobSub index file, v7
                id: en, index: 2
                timestamp: 00:00:01:000, filepos: 000000000
                id: zh, index: 0
                timestamp: 00:00:02:000, filepos: 000000800
                id: -- , index: 7
                timestamp: 00:00:03:000, filepos: 000001000
                id: en, index: 35
                """);
        Files.write(directory.resolve("第一集.SUB"), new byte[] {1, 2, 3});
        Files.writeString(directory.resolve("第一集.fr.idx"), "id: fr, index: 0");
        Files.writeString(directory.resolve("秘密.txt"), "do not serve");
        Files.createSymbolicLink(directory.resolve("第一集.ja.srt"), directory.resolve("秘密.txt"));

        NasSubtitleLibrary library = library();
        var tracks = library.discover(video("第一集.mp4"));
        assertEquals(List.of("zh", "en", "und"), tracks.stream().map(
                NasSubtitleLibrary.Track::language).toList());
        assertEquals(List.of(0, 2, 7), tracks.stream().map(NasSubtitleLibrary.Track::streamIndex).toList());
        assertEquals(List.of("vobsub", "vobsub", "vobsub"), tracks.stream().map(
                NasSubtitleLibrary.Track::format).toList());
        assertNotEquals(tracks.get(0).id(), tracks.get(1).id());
        assertEquals(tracks.get(0).dataPath(), tracks.get(1).dataPath());
        try (var binary = library.open(tracks.get(0), true)) {
            assertEquals(3, binary.length());
            assertArrayEquals(new byte[] {1, 2, 3}, binary.input().readAllBytes());
        }

        Files.delete(directory.resolve("第一集.SUB"));
        Files.createSymbolicLink(directory.resolve("第一集.SUB"), directory.resolve("秘密.txt"));
        assertThrows(IOException.class, () -> library.open(tracks.get(0), true));
        assertTrue(library.discover(video("第一集.mp4")).isEmpty());
    }
}
