package com.backend.meety.domain.recording.storage;

import com.backend.meety.global.config.S3Properties;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class AudioFileStorage {

    private final S3Presigner presigner;
    private final S3Client s3Client;
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

    public URL createDownloadUrl(String storageKey, Duration validity) {
        return presignDownload(GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .build(), validity);
    }

    public URL createDownloadUrl(String storageKey, String filename, Duration validity) {
        return presignDownload(GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .responseContentDisposition(ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .build(), validity);
    }

    public void deleteObject(String storageKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .build());
    }

    public Optional<Long> findObjectSize(String storageKey) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .build();
        try {
            return Optional.of(s3Client.headObject(request).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    private URL presignDownload(GetObjectRequest getObjectRequest, Duration validity) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(validity)
                .getObjectRequest(getObjectRequest)
                .build();
        return presigner.presignGetObject(presignRequest).url();
    }
}
