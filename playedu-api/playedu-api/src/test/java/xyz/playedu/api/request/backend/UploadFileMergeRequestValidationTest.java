package xyz.playedu.api.request.backend;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

class UploadFileMergeRequestValidationTest {
    private static final long MAX_BYTES = 10L * 1024 * 1024 * 1024;

    @Test void exactlyTenGiBPassesSizeValidation() {
        UploadFileMergeRequest request = validRequest();
        request.setSize(MAX_BYTES);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test void zeroAndOneByteOverLimitAreRejected() {
        UploadFileMergeRequest request = validRequest();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            for (long size : new long[] {0, MAX_BYTES + 1}) {
                request.setSize(size);
                assertFalse(factory.getValidator().validate(request).isEmpty());
            }
        }
    }

    private UploadFileMergeRequest validRequest() {
        UploadFileMergeRequest request = new UploadFileMergeRequest();
        request.setFilename("videos/test.mp4");
        request.setUploadId("test-upload-id");
        request.setOriginalFilename("test.mp4");
        request.setDuration(10);
        request.setExtension("mp4");
        request.setSize(MAX_BYTES);
        return request;
    }
}
