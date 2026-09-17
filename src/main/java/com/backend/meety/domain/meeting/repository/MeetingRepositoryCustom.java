package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.support.MeetingListSearchCondition;
import java.time.LocalDate;
import java.util.List;

public interface MeetingRepositoryCustom {

    List<LocalDate> findMeetingDatesByCondition(MeetingListSearchCondition condition);

    List<Meeting> findMeetingsByDates(MeetingListSearchCondition condition, List<LocalDate> dates);
}
