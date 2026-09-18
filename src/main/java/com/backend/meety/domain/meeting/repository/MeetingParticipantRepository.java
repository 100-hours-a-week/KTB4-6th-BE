package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, Long> {

    Optional<MeetingParticipant> findByMeetingIdAndTeamMemberId(Long meetingId, Long teamMemberId);

    boolean existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
            Long meetingId, Long teamMemberId, ParticipationStatus participationStatus
    );

    @Query("""
            select p
            from MeetingParticipant p
            join fetch p.teamMember
            where p.meeting.id = :meetingId
              and p.participationStatus = com.backend.meety.domain.meeting.entity.ParticipationStatus.JOINED
              and p.deletedAt is null
            order by p.createdAt asc, p.id asc
            """)
    List<MeetingParticipant> findCurrentParticipantsByMeetingId(@Param("meetingId") Long meetingId);
}
