package xyz.playedu.common.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ListPartsRequest;
import com.amazonaws.services.s3.model.PartListing;
import com.amazonaws.services.s3.model.PartSummary;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import xyz.playedu.common.exception.ServiceException;
import xyz.playedu.common.types.config.S3Config;

class S3MultipartPaginationTest {
    AmazonS3 client = mock(AmazonS3.class);
    AmazonS3ClientBuilder builder = mock(AmazonS3ClientBuilder.class, RETURNS_SELF);
    S3Config config = new S3Config();
    S3Util storage;

    @BeforeEach void setup() throws Exception {
        config.setBucket("videos");
        config.setAccessKey("test-access");
        config.setSecretKey("test-secret");
        config.setRegion("us-east-1");
        config.setEndpoint("https://storage.example.com");
        storage = new S3Util(config);
        when(builder.build()).thenReturn(client);
        when(client.doesBucketExistV2("videos")).thenReturn(true);
        when(client.getBucketAcl("videos")).thenReturn(new AccessControlList());
        when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(new URL("https://storage.example.com/videos/video.mp4"));
    }

    @Test void listingFollowsAllThreePagesForTenGiBFile() {
        List<Integer> markers = new ArrayList<>();
        when(client.listParts(any(ListPartsRequest.class))).thenAnswer(invocation -> {
            ListPartsRequest request = invocation.getArgument(0);
            markers.add(request.getPartNumberMarker());
            assertEquals(1000, request.getMaxParts());
            return switch (request.getPartNumberMarker()) {
                case 0 -> page(1, 1000, true);
                case 1000 -> page(1001, 2000, true);
                case 2000 -> page(2001, 2048, false);
                default -> throw new AssertionError("Unexpected page marker");
            };
        });
        try (MockedStatic<AmazonS3ClientBuilder> factory = mockStatic(AmazonS3ClientBuilder.class)) {
            factory.when(AmazonS3ClientBuilder::standard).thenReturn(builder);
            List<PartSummary> listed = storage.listParts("upload-id", "video.mp4");
            assertEquals(List.of(0, 1000, 2000), markers);
            assertEquals(IntStream.rangeClosed(1, 2048).boxed().toList(),
                    listed.stream().map(PartSummary::getPartNumber).toList());
        }
    }

    @Test void mergeIncludesEveryPartInAscendingOrderAcrossPages() {
        when(client.listParts(any(ListPartsRequest.class))).thenAnswer(invocation -> {
            ListPartsRequest request = invocation.getArgument(0);
            return switch (request.getPartNumberMarker()) {
                case 0 -> page(1, 1000, true);
                case 1000 -> page(1001, 2000, true);
                case 2000 -> page(2001, 2048, false);
                default -> throw new AssertionError("Unexpected page marker");
            };
        });
        try (MockedStatic<AmazonS3ClientBuilder> factory = mockStatic(AmazonS3ClientBuilder.class)) {
            factory.when(AmazonS3ClientBuilder::standard).thenReturn(builder);
            storage.merge("video.mp4", "upload-id");
            ArgumentCaptor<CompleteMultipartUploadRequest> merged =
                    ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
            verify(client).completeMultipartUpload(merged.capture());
            assertEquals("videos", merged.getValue().getBucketName());
            assertEquals("video.mp4", merged.getValue().getKey());
            assertEquals("upload-id", merged.getValue().getUploadId());
            assertEquals(IntStream.rangeClosed(1, 2048).boxed().toList(),
                    merged.getValue().getPartETags().stream().map(item -> item.getPartNumber()).toList());
            assertEquals("etag-2048", merged.getValue().getPartETags().get(2047).getETag());
        }
    }

    @Test void emptyUploadDoesNotAttemptCompletion() {
        when(client.listParts(any(ListPartsRequest.class))).thenReturn(page(1, 0, false));
        try (MockedStatic<AmazonS3ClientBuilder> factory = mockStatic(AmazonS3ClientBuilder.class)) {
            factory.when(AmazonS3ClientBuilder::standard).thenReturn(builder);
            assertThrows(ServiceException.class, () -> storage.merge("video.mp4", "upload-id"));
            verify(client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        }
    }

    @Test void nonAdvancingPaginationDoesNotLoopOrCompletePartialUpload() {
        PartListing broken = page(1, 3, true);
        broken.setNextPartNumberMarker(0);
        when(client.listParts(any(ListPartsRequest.class))).thenReturn(broken);
        try (MockedStatic<AmazonS3ClientBuilder> factory = mockStatic(AmazonS3ClientBuilder.class)) {
            factory.when(AmazonS3ClientBuilder::standard).thenReturn(builder);
            assertThrows(ServiceException.class, () -> storage.merge("video.mp4", "upload-id"));
            verify(client, times(1)).listParts(any(ListPartsRequest.class));
            verify(client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        }
    }

    private PartListing page(int first, int last, boolean truncated) {
        PartListing listing = new PartListing();
        listing.setTruncated(truncated);
        listing.setNextPartNumberMarker(last);
        List<PartSummary> items = new ArrayList<>();
        for (int number = first; number <= last; number++) {
            PartSummary item = new PartSummary();
            item.setPartNumber(number);
            item.setETag("etag-" + number);
            items.add(item);
        }
        Collections.reverse(items);
        listing.setParts(items);
        return listing;
    }
}
