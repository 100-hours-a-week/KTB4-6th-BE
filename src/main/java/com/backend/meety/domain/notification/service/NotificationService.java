package com.backend.meety.domain.notification.service;

import com.backend.meety.domain.notification.dto.NotificationItemResponse;
import com.backend.meety.domain.notification.dto.NotificationListResponse;
import com.backend.meety.domain.notification.dto.NotificationReadRequest;
import com.backend.meety.domain.notification.dto.NotificationReadResponse;
import com.backend.meety.domain.notification.dto.NotificationsReadResponse;
import com.backend.meety.domain.notification.entity.Notification;
import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.exception.NotificationErrorCode;
import com.backend.meety.domain.notification.exception.NotificationException;
import com.backend.meety.domain.notification.repository.NotificationRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.event.TeamMemberJoinType;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;

    private static final String NEW_JOINED_MEMBER_BODY_FORMAT = "%s 님이 팀에 합류했습니다";
    private static final String NEW_EXISTING_MEMBER_JOINED_BODY_FORMAT = "%s 님이 팀에 합류했습니다";
    private static final String REJOIN_JOINED_MEMBER_BODY_FORMAT = "%s 님이 팀에 합류했습니다";
    private static final String REJOIN_EXISTING_MEMBER_JOINED_BODY_FORMAT = "%s 님이 팀에 다시 합류했습니다";

    private final TeamMemberRepository teamMemberRepository;
    private final NotificationWriter notificationWriter;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(Long userId, String cursor, Integer size) {
        Long parsedCursor = parseCursor(cursor);
        int pageSize = resolvePageSize(size);
        Long teamId = findActiveTeamId(userId);

        List<Notification> notifications = notificationRepository.findPageByUserIdAndTeamId(
                userId, teamId, parsedCursor, PageRequest.of(0, pageSize + 1));
        boolean hasNext = notifications.size() > pageSize;
        List<Notification> page = hasNext ? notifications.subList(0, pageSize) : notifications;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        long unreadCount = notificationRepository.countUnreadByUserIdAndTeamId(userId, teamId);

        return new NotificationListResponse(
                page.stream()
                        .map(NotificationItemResponse::from)
                        .toList(),
                unreadCount,
                nextCursor,
                hasNext
        );
    }

    @Transactional
    public NotificationsReadResponse markAllAsRead(Long userId, NotificationReadRequest request) {
        validateReadRequest(request);
        Long teamId = findActiveTeamId(userId);
        Long maxId = notificationRepository.findMaxIdByUserIdAndTeamId(userId, teamId);
        int readCount = maxId == null ? 0
                : notificationRepository.markUnreadAsReadUntilId(userId, teamId, maxId);
        long unreadCount = notificationRepository.countUnreadByUserIdAndTeamId(userId, teamId);
        return new NotificationsReadResponse(readCount, unreadCount);
    }

    @Transactional
    public NotificationReadResponse markAsRead(Long userId, Long notificationId, NotificationReadRequest request) {
        validateReadRequest(request);
        Long teamId = findActiveTeamId(userId);
        Notification notification = notificationRepository
                .findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(notificationId, userId, teamId)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markAsRead();
        long unreadCount = notificationRepository.countUnreadByUserIdAndTeamId(userId, teamId);
        return new NotificationReadResponse(notification.getId(), notification.isRead(), unreadCount);
    }

    @Transactional
    public void delete(Long userId, Long notificationId) {
        Long teamId = findActiveTeamId(userId);
        Notification notification = notificationRepository
                .findByIdAndUserIdAndTeamIdAndDeletedAtIsNull(notificationId, userId, teamId)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.delete(LocalDateTime.now(clock));
    }

    public void notifyActiveTeamMembers(
            Long teamId,
            NotificationType type,
            String idempotencyKey,
            NotificationReferenceType referenceType,
            Long referenceId,
            String body
    ) {
        notifyActiveTeamMembers(teamId, type, idempotencyKey, referenceType, referenceId, recipient -> body);
    }

    public void notifyMemberJoined(
            Long teamId,
            Long joinedUserId,
            String joinedDisplayName,
            TeamMemberJoinType joinType,
            String idempotencyKey
    ) {
        notifyActiveTeamMembers(
                teamId,
                NotificationType.MEMBER_JOINED,
                idempotencyKey,
                NotificationReferenceType.TEAM,
                null,
                recipient -> memberJoinedBody(recipient, joinedUserId, joinedDisplayName, joinType)
        );
    }

    private void notifyActiveTeamMembers(
            Long teamId,
            NotificationType type,
            String idempotencyKey,
            NotificationReferenceType referenceType,
            Long referenceId,
            Function<NotificationRecipient, String> bodyFactory
    ) {
        List<NotificationRecipient> recipients = teamMemberRepository
                .findAllByTeamIdAndMembershipStatusWithUserAndTeam(teamId, MembershipStatus.ACTIVE)
                .stream()
                .map(this::toRecipient)
                .toList();

        for (NotificationRecipient recipient : recipients) {
            create(recipient, type, idempotencyKey, referenceType, referenceId, bodyFactory.apply(recipient));
        }
    }

    private NotificationRecipient toRecipient(TeamMember member) {
        return new NotificationRecipient(member.getTeam().getId(), member.getUser().getId());
    }

    private void create(
            NotificationRecipient recipient,
            NotificationType type,
            String idempotencyKey,
            NotificationReferenceType referenceType,
            Long referenceId,
            String body
    ) {
        try {
            notificationWriter.create(new NotificationWriteCommand(
                    recipient.teamId(), recipient.userId(), type, idempotencyKey, referenceType, referenceId, body));
        } catch (DataIntegrityViolationException e) {
            log.debug("이미 생성된 알림입니다. userId={}, idempotencyKey={}", recipient.userId(), idempotencyKey);
        } catch (RuntimeException e) {
            log.error("알림 생성에 실패했습니다. userId={}, type={}, idempotencyKey={}",
                    recipient.userId(), type, idempotencyKey, e);
        }
    }

    String memberJoinedBody(
            NotificationRecipient recipient,
            Long joinedUserId,
            String joinedDisplayName,
            TeamMemberJoinType joinType
    ) {
        if (recipient.isUser(joinedUserId)) {
            return joinedMemberBody(joinedDisplayName, joinType);
        }
        return existingMemberJoinedBody(joinedDisplayName, joinType);
    }

    String joinedMemberBody(String joinedDisplayName, TeamMemberJoinType joinType) {
        return switch (joinType) {
            case NEW -> newJoinedMemberBody(joinedDisplayName);
            case REJOIN -> rejoinedMemberBody(joinedDisplayName);
        };
    }

    String existingMemberJoinedBody(String joinedDisplayName, TeamMemberJoinType joinType) {
        return switch (joinType) {
            case NEW -> newExistingMemberJoinedBody(joinedDisplayName);
            case REJOIN -> rejoinedExistingMemberJoinedBody(joinedDisplayName);
        };
    }

    String newJoinedMemberBody(String joinedDisplayName) {
        // TODO: 신규 가입자 본인 문구는 제품 정책 확정 후 변경한다.
        return NEW_JOINED_MEMBER_BODY_FORMAT.formatted(joinedDisplayName);
    }

    String newExistingMemberJoinedBody(String joinedDisplayName) {
        return NEW_EXISTING_MEMBER_JOINED_BODY_FORMAT.formatted(joinedDisplayName);
    }

    String rejoinedMemberBody(String joinedDisplayName) {
        // TODO: 재가입자 본인 문구는 제품 정책 확정 후 변경한다.
        return REJOIN_JOINED_MEMBER_BODY_FORMAT.formatted(joinedDisplayName);
    }

    String rejoinedExistingMemberJoinedBody(String joinedDisplayName) {
        return REJOIN_EXISTING_MEMBER_JOINED_BODY_FORMAT.formatted(joinedDisplayName);
    }

    private Long findActiveTeamId(Long userId) {
        return teamMemberRepository.findByUserIdAndMembershipStatusWithTeam(userId, MembershipStatus.ACTIVE)
                .map(member -> member.getTeam().getId())
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBERSHIP_REQUIRED));
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(cursor);
            if (parsed <= 0) {
                throw new NotificationException(NotificationErrorCode.INVALID_CURSOR);
            }
            return parsed;
        } catch (NotificationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NotificationException(NotificationErrorCode.INVALID_CURSOR);
        }
    }

    private int resolvePageSize(Integer size) {
        int resolved = size == null ? DEFAULT_PAGE_SIZE : size;
        if (resolved < MIN_PAGE_SIZE || resolved > MAX_PAGE_SIZE) {
            throw new NotificationException(NotificationErrorCode.INVALID_PAGE_SIZE);
        }
        return resolved;
    }

    private void validateReadRequest(NotificationReadRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.isRead())) {
            throw new NotificationException(NotificationErrorCode.INVALID_NOTIFICATION_READ_STATUS);
        }
    }
}
