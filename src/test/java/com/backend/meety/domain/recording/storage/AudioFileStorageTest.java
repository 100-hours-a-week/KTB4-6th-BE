package com.backend.meety.domain.recording.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.global.config.S3Properties;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class AudioFileStorageTest {

    @Test
    @DisplayName("버킷, storage key, 유효시간이 반영된 presigned PUT URL을 발급한다")
    void presignsPutObjectUrl() {
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIAFAKE", "secretfake")))
                .build();
        AudioFileStorage storage = new AudioFileStorage(
                presigner, new S3Properties("meety-audio", "ap-northeast-2"));

        URL url = storage.createUploadUrl("recordings/100/700/abc.mp4", "audio/mp4", Duration.ofMinutes(10));

        assertThat(url.toString())
                .startsWith("https://meety-audio.s3.ap-northeast-2.amazonaws.com/recordings/100/700/abc.mp4")
                .contains("X-Amz-Expires=600")
                .contains("X-Amz-Signature=");
    }
}
