package com.backend.meety.domain.recording.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.session;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.entity.AudioFileStatus;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.exception.AudioFileErrorCode;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.repository.AudioFileRepository;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.exception.BaseCode;
import com.backend.meety.global.exception.BusinessException;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AudioFileServiceTest {

    private static final String UPLOAD_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/recordings/upload";

    private final RecordingSessionRepository recordings = mock(RecordingSessionRepository.class);
    private final AudioFileRepository audioFiles = mock(AudioFileRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final AudioFileStorage storage = mock(AudioFileStorage.class);
    private final AudioFileService service = new AudioFileService(
            recordings, audioFiles, members, storage, CLOCK);

    private Team team;
    private TeamMember starter;
    private Meeting meeting;
    private RecordingSession recordingSession;

    @BeforeEach
    void setUp() throws Exception {
        team = team();
        starter = member(team);
        meeting = meeting(team, starter);
        recordingSession = session(meeting, starter);
        when(recordings.findByIdAndDeletedAtIsNull(700L)).thenReturn(Optional.of(recordingSession));
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(starter));
        when(audioFiles.save(any(AudioFile.class))).thenAnswer(call -> withId(call.getArgument(0), 800L));
        when(storage.createUploadUrl(any(), any(), any())).thenReturn(URI.create(UPLOAD_URL).toURL());
    }

    @Test
    @DisplayName("녹음 시작자는 업로드 URL을 발급받는다")
    void createUploadUrlSucceeds() {
        AudioFileUploadUrlResponse response = service.createUploadUrl(1L, 700L, "audio/mp4");

        assertThat(response.audioFileId()).isEqualTo(800L);
        assertThat(response.uploadUrl()).isEqualTo(UPLOAD_URL);
        assertThat(response.uploadUrlExpiresAt()).isEqualTo(NOW.plus(AudioFilePolicy.UPLOAD_URL_VALIDITY));
    }

    @Test
    @DisplayName("발급한 음성 파일은 UPLOADING 상태로 저장된다")
    void savedAudioFileIsUploading() {
        service.createUploadUrl(1L, 700L, "audio/mp4");

        ArgumentCaptor<AudioFile> captor = ArgumentCaptor.forClass(AudioFile.class);
        verify(audioFiles).save(captor.capture());
        AudioFile saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AudioFileStatus.UPLOADING);
        assertThat(saved.getContentType()).isEqualTo("audio/mp4");
        assertThat(saved.getStorageKey()).startsWith("recordings/100/700/").endsWith(".mp4");
        assertThat(saved.getStoredAt()).isNull();
    }

    @Test
    @DisplayName("presigned URL은 저장된 storage key와 정책 유효시간으로 발급한다")
    void presignsWithStoredKeyAndValidity() {
        service.createUploadUrl(1L, 700L, "audio/mp4");

        ArgumentCaptor<AudioFile> captor = ArgumentCaptor.forClass(AudioFile.class);
        verify(audioFiles).save(captor.capture());
        verify(storage).createUploadUrl(
                eq(captor.getValue().getStorageKey()), eq("audio/mp4"), eq(AudioFilePolicy.UPLOAD_URL_VALIDITY));
    }

    @Test
    @DisplayName("mp4가 아닌 형식은 발급하지 않는다")
    void rejectsUnsupportedContentType() {
        assertCode(() -> service.createUploadUrl(1L, 700L, "audio/webm"), AudioFileErrorCode.INVALID_AUDIO_CONTENT_TYPE);

        verify(audioFiles, never()).save(any());
    }

    @Test
    @DisplayName("녹음 세션이 없으면 발급하지 않는다")
    void rejectsMissingRecordingSession() {
        when(recordings.findByIdAndDeletedAtIsNull(700L)).thenReturn(Optional.empty());

        assertCode(() -> service.createUploadUrl(1L, 700L, "audio/mp4"), RecordingErrorCode.RECORDING_SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("활성 팀원이 아니면 발급하지 않는다")
    void rejectsNonActiveMember() {
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertCode(() -> service.createUploadUrl(1L, 700L, "audio/mp4"), AudioFileErrorCode.AUDIO_FILE_CREATE_FORBIDDEN);
    }

    @Test
    @DisplayName("녹음을 시작하지 않은 팀원은 발급하지 않는다")
    void rejectsNonStarter() {
        TeamMember other = withId(TeamMember.createLeader(withId(User.create(), 9L), team, "다른 팀원"), 11L);
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 9L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(other));

        assertCode(() -> service.createUploadUrl(9L, 700L, "audio/mp4"), AudioFileErrorCode.AUDIO_FILE_CREATE_FORBIDDEN);

        verify(audioFiles, never()).save(any());
    }

    @Test
    @DisplayName("이미 발급된 음성 파일이 있으면 중복 발급하지 않는다")
    void rejectsDuplicateAudioFile() {
        when(audioFiles.existsByRecordingSessionIdAndDeletedAtIsNull(700L)).thenReturn(true);

        assertCode(() -> service.createUploadUrl(1L, 700L, "audio/mp4"), AudioFileErrorCode.AUDIO_FILE_ALREADY_EXISTS);

        verify(audioFiles, never()).save(any());
    }

    @Test
    @DisplayName("presigned URL 발급에 실패하면 500 에러 코드로 변환한다")
    void translatesPresignFailure() {
        when(storage.createUploadUrl(any(), any(), any())).thenThrow(new RuntimeException("presign failed"));

        assertCode(() -> service.createUploadUrl(1L, 700L, "audio/mp4"), AudioFileErrorCode.AUDIO_UPLOAD_URL_CREATE_FAILED);
    }

    private void assertCode(Runnable action, BaseCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
