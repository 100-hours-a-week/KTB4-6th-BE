package com.backend.meety.domain.recording.storage;

import com.backend.meety.global.config.S3Properties;
import java.net.URL;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class AudioFileStorage {

    private final S3Presigner presigner;
    private final S3Properties properties;

    public URL createUploadUrl(String storageKey, String contentType, Duration validity) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(validity)
                .putObjectRequest(putObjectRequest)
                .build();
        return presigner.presignPutObject(presignRequest).url();
    }
}
