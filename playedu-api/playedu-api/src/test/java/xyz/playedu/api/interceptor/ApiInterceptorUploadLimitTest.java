package xyz.playedu.api.interceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import xyz.playedu.common.config.PlayEduConfig;
import xyz.playedu.common.service.RateLimiterService;

class ApiInterceptorUploadLimitTest {
    private static final String IP = "192.0.2.10";
    private static final String UPLOAD_KEY = "api-upload-limiter:" + IP;
    private static final String API_KEY = "api-limiter:" + IP;
    private final Map<String, Long> counts = new HashMap<>();
    private final RateLimiterService limiter = mock(RateLimiterService.class);
    private final ApiInterceptor interceptor = new ApiInterceptor();

    @BeforeEach void setup() {
        PlayEduConfig config = new PlayEduConfig();
        config.setLimiterLimit(360L);
        config.setLimiterDuration(60L);
        ReflectionTestUtils.setField(interceptor, "playEduConfig", config);
        ReflectionTestUtils.setField(interceptor, "rateLimiterService", limiter);
        when(limiter.current(anyString(), eq(60L))).thenAnswer(invocation ->
                counts.merge(invocation.getArgument(0), 1L, Long::sum));
    }

    @AfterEach void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void uploadCreationAndCompletionShareOneIpBucket() throws Exception {
        Outcome create = invoke("/backend/v1/upload/minio/upload-id", "GET");
        Outcome complete = invoke("/backend/v1/upload/minio/merge-file", "POST");
        assertTrue(create.allowed());
        assertTrue(complete.allowed());
        assertEquals(Map.of(UPLOAD_KEY, 2L), counts);
        verify(limiter, times(2)).current(UPLOAD_KEY, 60L);
        assertEquals("360", complete.response().getHeader("X-RateLimit-Limit"));
        assertEquals("358", complete.response().getHeader("X-RateLimit-Remaining"));
    }

    @Test void exhaustedUploadBucketStillThrottlesButDoesNotBlockPlayback() throws Exception {
        counts.put(UPLOAD_KEY, 359L);
        Outcome upload = invoke("/backend/v1/upload/minio/merge-file", "POST");
        assertFalse(upload.allowed());
        assertEquals(429, upload.response().getStatus());
        assertEquals("360", upload.response().getHeader("X-RateLimit-Limit"));
        assertEquals("0", upload.response().getHeader("X-RateLimit-Remaining"));
        Outcome playback = invoke("/api/v1/course/7/hour/9/play", "GET");
        assertTrue(playback.allowed());
        assertEquals(200, playback.response().getStatus());
        assertEquals(1L, counts.get(API_KEY));
        assertEquals("359", playback.response().getHeader("X-RateLimit-Remaining"));
        verify(limiter).current(API_KEY, 60L);
    }

    @Test void otherUploadAndNormalRoutesKeepOriginalBucket() throws Exception {
        for (String path : new String[] {"/backend/v1/upload/minio/list-parts",
                "/backend/v1/upload/minio/upload-id-extra", "/backend/v1/course/index"}) {
            assertTrue(invoke(path, "GET").allowed());
        }
        assertEquals(Map.of(API_KEY, 3L), counts);
        verify(limiter, times(3)).current(API_KEY, 60L);
    }

    @Test void presignedPartUrlKeepsExistingWhitelistBypass() throws Exception {
        counts.put(UPLOAD_KEY, 360L);
        counts.put(API_KEY, 360L);
        Outcome presign = invoke("/backend/v1/upload/minio/pre-sign-url", "GET");
        assertTrue(presign.allowed());
        assertNull(presign.response().getHeader("X-RateLimit-Limit"));
        verifyNoInteractions(limiter);
    }

    private Outcome invoke(String path, String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(IP);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new Outcome(interceptor.preHandle(request, response, new Object()), response);
    }

    private record Outcome(boolean allowed, MockHttpServletResponse response) {}
}
