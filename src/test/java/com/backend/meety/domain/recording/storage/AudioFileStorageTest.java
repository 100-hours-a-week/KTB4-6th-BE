package com.backend.meety.domain.recording.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.global.config.S3Properties;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class AudioFileStorageTest {

    private static final String STORAGE_KEY = "recordings/100/700/abc.mp4";
    private static final S3Properties PROPERTIES = new S3Properties("meety-audio", "us-east-2");

    private final S3Client s3Client = mock(S3Client.class);

    private AudioFileStorage storage(S3Presigner presigner) {
        return new AudioFileStorage(presigner, s3Client, PROPERTIES);
    }

    private S3Presigner presigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIAFAKE", "secretfake")))
                .build();
    }

    @Test
    @DisplayName("버킷, storage key, 유효시간이 반영된 presigned PUT URL을 발급한다")
    void presignsPutObjectUrl() {
        URL url = storage(presigner())
                .createUploadUrl(STORAGE_KEY, "audio/mp4", Duration.ofMinutes(10));

        assertThat(url.toString())
                .startsWith("https://meety-audio.s3.us-east-2.amazonaws.com/" + STORAGE_KEY)
                .contains("X-Amz-Expires=600")
                .contains("X-Amz-Signature=");
    }

    @Test
    @DisplayName("S3에 객체가 있으면 크기를 반환한다")
    void returnsObjectSize() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1_024L).build());

        Optional<Long> size = storage(presigner()).findObjectSize(STORAGE_KEY);

        assertThat(size).contains(1_024L);
        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("meety-audio");
        assertThat(captor.getValue().key()).isEqualTo(STORAGE_KEY);
    }

    @Test
    @DisplayName("S3에 객체가 없으면 빈 값을 반환한다")
    void returnsEmptyWhenObjectMissing() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThat(storage(presigner()).findObjectSize(STORAGE_KEY)).isEmpty();
    }
}
