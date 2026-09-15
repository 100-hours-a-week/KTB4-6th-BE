package com.backend.meety.domain.meeting.repository;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.support.MeetingListSearchCondition;
import java.util.List;

public interface MeetingRepositoryCustom {

    List<Meeting> findMeetingsByCondition(MeetingListSearchCondition condition);
}
