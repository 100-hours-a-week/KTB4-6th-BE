package com.backend.meety.domain.recording;

import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

public final class RecordingFixtures {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T05:20:00Z"), ZoneId.of("Asia/Seoul"));
    public static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private RecordingFixtures() {
    }

    public static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    public static Team team() {
        return withId(Team.create("녹음 테스트"), 2L);
    }

    public static TeamMember member(Team team) {
        return withId(TeamMember.createLeader(withId(User.create(), 1L), team, "시작자"), 10L);
    }

    public static Meeting meeting(Team team, TeamMember member) {
        return withId(Meeting.create(team, member, "회의", "테스트", null, NOW, 30), 100L);
    }

    public static RecordingSession session(Meeting meeting, TeamMember member) {
        return withId(RecordingSession.start(meeting, member, NOW), 700L);
    }

    public static TeamCredit credit(Team team, long balance) {
        TeamCredit credit = BeanUtils.instantiateClass(TeamCredit.class);
        ReflectionTestUtils.setField(credit, "team", team);
        ReflectionTestUtils.setField(credit, "balance", balance);
        return credit;
    }
}
