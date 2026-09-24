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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.recording.dto.AudioFileDeleteResponse;
import com.backend.meety.domain.recording.dto.AudioFileDetailResponse;
import com.backend.meety.domain.recording.dto.AudioFileDownloadUrlResponse;
import com.backend.meety.domain.recording.dto.AudioFileResponse;
import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.entity.AudioFileStatus;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.exception.AudioFileErrorCode;
import com.backend.meety.domain.recording.event.AudioFileDeleteRequestedEvent;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.repository.AudioFileRepository;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.storage.AudioFileStorage;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.user.entity.User;
import com.backend.meety.global.exception.BaseCode;
import com.backend.meety.global.exception.BusinessException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AudioFileServiceTest {

    private static final String UPLOAD_URL = "https://bucket.s3.us-east-2.amazonaws.com/recordings/upload";
    private static final String STORAGE_KEY = "recordings/100/700/abc.mp4";
    private static final String DOWNLOAD_URL = "https://bucket.s3.us-east-2.amazonaws.com/recordings/download";

    private final RecordingSessionRepository recordings = mock(RecordingSessionRepository.class);
    private final AudioFileRepository audioFiles = mock(AudioFileRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final AudioFileStorage storage = mock(AudioFileStorage.class);
    private final List<Object> publishedEvents = new ArrayList<>();
    private final AudioFileService service = new AudioFileService(
            recordings, audioFiles, members, storage, CLOCK, publishedEvents::add);

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

    @Nested
    @DisplayName("업로드 완료 처리")
    class CompleteUpload {

        private AudioFile audioFile;

        @BeforeEach
        void setUpAudioFile() {
            audioFile = withId(AudioFile.create(recordingSession, STORAGE_KEY, "audio/mp4"), 800L);
            when(audioFiles.findByIdForUpdateAndDeletedAtIsNull(800L)).thenReturn(Optional.of(audioFile));
            when(storage.findObjectSize(STORAGE_KEY)).thenReturn(Optional.of(135_000_000L));
        }

        @Test
        @DisplayName("S3에 저장된 크기로 AVAILABLE 전이한다")
        void marksAvailableWithS3Size() {
            AudioFileResponse response = service.completeUpload(1L, 800L, 135_000_000L, 2_700_000L);

            assertThat(response.status()).isEqualTo(AudioFileStatus.AVAILABLE);
            assertThat(response.fileSizeBytes()).isEqualTo(135_000_000L);
            assertThat(response.durationMs()).isEqualTo(2_700_000L);
            assertThat(response.storedAt()).isEqualTo(NOW);
            assertThat(response.expiresAt()).isEqualTo(NOW.plus(AudioFilePolicy.RETENTION));
        }

        @Test
        @DisplayName("FE가 보낸 크기가 아니라 S3 크기를 저장한다")
        void ignoresClientReportedSize() {
            when(storage.findObjectSize(STORAGE_KEY)).thenReturn(Optional.of(999L));

            assertThat(service.completeUpload(1L, 800L, 135_000_000L, 2_700_000L).fileSizeBytes())
                    .isEqualTo(999L);
        }

        @Test
        @DisplayName("FE가 보낸 크기와 S3 크기가 달라도 S3 크기로 저장하고 진행한다")
        void keepsGoingWhenReportedSizeMismatches() {
            when(storage.findObjectSize(STORAGE_KEY)).thenReturn(Optional.of(500L));

            AudioFileResponse response = service.completeUpload(1L, 800L, 999_999L, 2_700_000L);

            assertThat(response.status()).isEqualTo(AudioFileStatus.AVAILABLE);
            assertThat(response.fileSizeBytes()).isEqualTo(500L);
        }

        @Test
        @DisplayName("음성 파일이 없으면 404다")
        void rejectsMissingAudioFile() {
            when(audioFiles.findByIdForUpdateAndDeletedAtIsNull(800L)).thenReturn(Optional.empty());

            assertCode(() -> service.completeUpload(1L, 800L, 1L, 1L), AudioFileErrorCode.AUDIO_FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("녹음 시작자가 아니면 403이다")
        void rejectsNonStarter() {
            TeamMember other = withId(TeamMember.createLeader(withId(User.create(), 9L), team, "다른 팀원"), 11L);
            when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 9L, MembershipStatus.ACTIVE))
                    .thenReturn(Optional.of(other));

            assertCode(() -> service.completeUpload(9L, 800L, 1L, 1L), AudioFileErrorCode.AUDIO_FILE_ACCESS_DENIED);
        }

        @Test
        @DisplayName("이미 AVAILABLE이면 409다")
        void rejectsAlreadyCompleted() {
            audioFile.markAvailable(1L, 1L, NOW, NOW);

            assertCode(() -> service.completeUpload(1L, 800L, 1L, 1L), AudioFileErrorCode.AUDIO_FILE_NOT_UPLOADING);
        }

        @Test
        @DisplayName("S3에 객체가 없으면 409다")
        void rejectsMissingObject() {
            when(storage.findObjectSize(STORAGE_KEY)).thenReturn(Optional.empty());

            assertCode(() -> service.completeUpload(1L, 800L, 1L, 1L), AudioFileErrorCode.AUDIO_OBJECT_NOT_FOUND);
            assertThat(audioFile.getStatus()).isEqualTo(AudioFileStatus.UPLOADING);
        }


        @Test
        @DisplayName("S3 조회가 실패하면 500으로 변환한다")
        void translatesHeadObjectFailure() {
            when(storage.findObjectSize(STORAGE_KEY)).thenThrow(new RuntimeException("head failed"));

            assertCode(() -> service.completeUpload(1L, 800L, 1L, 1L), AudioFileErrorCode.AUDIO_FILE_UPDATE_FAILED);
        }
    }

    @Nested
    @DisplayName("회의별 음성 파일 조회")
    class GetByMeeting {

        private AudioFile audioFile;

        @BeforeEach
        void setUpAudioFile() {
            audioFile = withId(AudioFile.create(recordingSession, STORAGE_KEY, "audio/mp4"), 800L);
            when(audioFiles.findByMeetingIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(audioFile));
            when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                    .thenReturn(true);
        }

        @Test
        @DisplayName("AVAILABLE이면 파일 정보를 반환한다")
        void returnsAvailableAudioFile() {
            audioFile.markAvailable(135_000_000L, 2_700_000L, NOW, NOW.plus(AudioFilePolicy.RETENTION));

            AudioFileDetailResponse response = service.getByMeeting(1L, 100L);

            assertThat(response.audioFileId()).isEqualTo(800L);
            assertThat(response.recordingSessionId()).isEqualTo(700L);
            assertThat(response.status()).isEqualTo(AudioFileStatus.AVAILABLE);
            assertThat(response.contentType()).isEqualTo("audio/mp4");
            assertThat(response.fileSizeBytes()).isEqualTo(135_000_000L);
            assertThat(response.durationMs()).isEqualTo(2_700_000L);
            assertThat(response.storedAt()).isEqualTo(NOW);
            assertThat(response.expiresAt()).isEqualTo(NOW.plus(AudioFilePolicy.RETENTION));
        }

        @Test
        @DisplayName("음성 파일이 없으면 404다")
        void rejectsMissingAudioFile() {
            when(audioFiles.findByMeetingIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

            assertCode(() -> service.getByMeeting(1L, 100L), AudioFileErrorCode.AUDIO_FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("활성 팀원이 아니면 403이다")
        void rejectsNonTeamMember() {
            when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                    .thenReturn(false);

            assertCode(() -> service.getByMeeting(1L, 100L), AudioFileErrorCode.AUDIO_FILE_ACCESS_DENIED);
        }

        @Test
        @DisplayName("업로드가 끝나지 않았으면 409다")
        void rejectsNotAvailable() {
            assertCode(() -> service.getByMeeting(1L, 100L), AudioFileErrorCode.AUDIO_FILE_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("녹음 시작자가 아니어도 같은 팀이면 조회된다")
        void allowsNonStarterTeamMember() {
            audioFile.markAvailable(1L, 1L, NOW, NOW);
            when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 9L, MembershipStatus.ACTIVE))
                    .thenReturn(true);

            assertThat(service.getByMeeting(9L, 100L).audioFileId()).isEqualTo(800L);
        }
    }

    @Nested
    @DisplayName("다운로드 URL 발급")
    class CreateDownloadUrl {

        private AudioFile audioFile;

        @BeforeEach
        void setUpAudioFile() throws Exception {
            audioFile = withId(AudioFile.create(recordingSession, STORAGE_KEY, "audio/mp4"), 800L);
            audioFile.markAvailable(1_024L, 1_000L, NOW, NOW.plus(AudioFilePolicy.RETENTION));
            when(audioFiles.findByIdAndDeletedAtIsNull(800L)).thenReturn(Optional.of(audioFile));
            when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                    .thenReturn(true);
            when(storage.createDownloadUrl(eq(STORAGE_KEY), any())).thenReturn(URI.create(DOWNLOAD_URL).toURL());
        }

        @Test
        @DisplayName("AVAILABLE이고 만료 전이면 다운로드 URL을 발급한다")
        void createsDownloadUrl() {
            AudioFileDownloadUrlResponse response = service.createDownloadUrl(1L, 800L);

            assertThat(response.downloadUrl()).isEqualTo(DOWNLOAD_URL);
            assertThat(response.downloadUrlExpiresAt())
                    .isEqualTo(NOW.plus(AudioFilePolicy.DOWNLOAD_URL_VALIDITY));
            verify(storage).createDownloadUrl(STORAGE_KEY, AudioFilePolicy.DOWNLOAD_URL_VALIDITY);
        }

        @Test
        @DisplayName("음성 파일이 없으면 404다")
        void rejectsMissingAudioFile() {
            when(audioFiles.findByIdAndDeletedAtIsNull(800L)).thenReturn(Optional.empty());

            assertCode(() -> service.createDownloadUrl(1L, 800L), AudioFileErrorCode.AUDIO_FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("활성 팀원이 아니면 403이다")
        void rejectsNonTeamMember() {
            when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                    .thenReturn(false);

            assertCode(() -> service.createDownloadUrl(1L, 800L), AudioFileErrorCode.AUDIO_FILE_ACCESS_DENIED);
        }

        @Test
        @DisplayName("AVAILABLE이 아니면 409다")
        void rejectsNotAvailable() {
            AudioFile uploading = withId(AudioFile.create(recordingSession, STORAGE_KEY, "audio/mp4"), 801L);
            when(audioFiles.findByIdAndDeletedAtIsNull(801L)).thenReturn(Optional.of(uploading));

            assertCode(() -> service.createDownloadUrl(1L, 801L), AudioFileErrorCode.AUDIO_FILE_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("보관 기간이 지났으면 410이다")
        void rejectsExpired() {
            AudioFile expired = withId(AudioFile.create(recordingSession, STORAGE_KEY, "audio/mp4"), 802L);
            expired.markAvailable(1L, 1L, NOW.minusDays(200), NOW.minusDays(1));
            when(audioFiles.findByIdAndDeletedAtIsNull(802L)).thenReturn(Optional.of(expired));

            assertCode(() -> service.createDownloadUrl(1L, 802L), AudioFileErrorCode.AUDIO_FILE_EXPIRED);
        }

        @Test
        @DisplayName("만료 시각 당일 정각은 만료로 처리한다")
        void treatsExactExpiryAsExpired() {
            AudioFile boundary = withId(AudioFile.create(recordingSession, STORAGE_KEY, "audio/mp4"), 803L);
            boundary.markAvailable(1L, 1L, NOW.minusDays(100), NOW);
            when(audioFiles.findByIdAndDeletedAtIsNull(803L)).thenReturn(Optional.of(boundary));

            assertCode(() -> service.createDownloadUrl(1L, 803L), AudioFileErrorCode.AUDIO_FILE_EXPIRED);
        }

        @Test
        @DisplayName("presign에 실패하면 500으로 변환한다")
        void translatesPresignFailure() {
            when(storage.createDownloadUrl(eq(STORAGE_KEY), any())).thenThrow(new RuntimeException("presign failed"));

            assertCode(() -> service.createDownloadUrl(1L, 800L),
                    AudioFileErrorCode.AUDIO_DOWNLOAD_URL_CREATE_FAILED);
        }
    }

    @Nested
    @DisplayName("원본 음성 삭제")
    class RequestDelete {

        private AudioFile audioFile;

        @BeforeEach
        void setUpAudioFile() {
            audioFile = withId(AudioFile.create(recordingSession, STORAGE_KEY, "audio/mp4"), 800L);
            audioFile.markAvailable(1_024L, 1_000L, NOW, NOW.plus(AudioFilePolicy.RETENTION));
            when(audioFiles.findByIdForUpdate(800L)).thenReturn(Optional.of(audioFile));
            when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                    .thenReturn(Optional.of(starter));
        }

        @Test
        @DisplayName("팀장이 요청하면 DELETE_PENDING으로 전이하고 삭제 이벤트를 발행한다")
        void marksDeletePendingAndPublishesEvent() {
            AudioFileDeleteResponse response = service.requestDelete(1L, 800L);

            assertThat(response.audioFileId()).isEqualTo(800L);
            assertThat(response.status()).isEqualTo(AudioFileStatus.DELETE_PENDING);
            assertThat(audioFile.getDeletedAt()).isEqualTo(NOW);
            assertThat(publishedEvents).singleElement()
                    .isEqualTo(new AudioFileDeleteRequestedEvent(800L, STORAGE_KEY));
        }

        @Test
        @DisplayName("음성 파일이 없으면 404다")
        void rejectsMissingAudioFile() {
            when(audioFiles.findByIdForUpdate(800L)).thenReturn(Optional.empty());

            assertCode(() -> service.requestDelete(1L, 800L), AudioFileErrorCode.AUDIO_FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("팀장이 아니면 403이다")
        void rejectsNonLeader() {
            TeamMember member = withId(TeamMember.createMember(withId(User.create(), 9L), team, "팀원"), 11L);
            when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 9L, MembershipStatus.ACTIVE))
                    .thenReturn(Optional.of(member));

            assertCode(() -> service.requestDelete(9L, 800L), TeamErrorCode.TEAM_LEADER_REQUIRED);
            assertThat(publishedEvents).isEmpty();
        }

        @Test
        @DisplayName("이미 삭제 진행 중이면 409다")
        void rejectsAlreadyDeleting() {
            audioFile.markDeletePending(NOW);

            assertCode(() -> service.requestDelete(1L, 800L), AudioFileErrorCode.AUDIO_FILE_DELETE_IN_PROGRESS);
        }

        @Test
        @DisplayName("이미 삭제 완료된 파일은 404다")
        void rejectsAlreadyDeleted() {
            audioFile.markDeletePending(NOW);
            audioFile.markDeleted();

            assertCode(() -> service.requestDelete(1L, 800L), AudioFileErrorCode.AUDIO_FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("S3 삭제에 성공하면 DELETED로 전이한다")
        void completesDelete() {
            when(audioFiles.findById(800L)).thenReturn(Optional.of(audioFile));

            service.completeDelete(800L, STORAGE_KEY);

            verify(storage).deleteObject(STORAGE_KEY);
            assertThat(audioFile.getStatus()).isEqualTo(AudioFileStatus.DELETED);
        }

        @Test
        @DisplayName("S3 삭제에 실패하면 DELETE_FAILED로 남긴다")
        void marksDeleteFailed() {
            when(audioFiles.findById(800L)).thenReturn(Optional.of(audioFile));
            doThrow(new RuntimeException("s3 down")).when(storage).deleteObject(STORAGE_KEY);

            service.completeDelete(800L, STORAGE_KEY);

            assertThat(audioFile.getStatus()).isEqualTo(AudioFileStatus.DELETE_FAILED);
        }
    }

    private void assertCode(Runnable action, BaseCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
