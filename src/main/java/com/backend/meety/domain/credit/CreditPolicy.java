package com.backend.meety.domain.credit;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 크레딧 정책값. 기획 정책이 바뀌면 이 클래스만 수정한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreditPolicy {

    /** 팀이 보유할 수 있는 최대 잔액. 적립 시 초과분은 버려진다. */
    public static final long MAX_BALANCE = 300L;

    /** 팀 생성 시 지급되는 초기 크레딧. */
    public static final long TEAM_CREATE_GRANT = 50L;

    /** 매주 월요일 00:00(KST) 자동 적립량. */
    public static final long WEEKLY_GRANT = 50L;

    /** 녹음 시작 시 차감량. */
    public static final long RECORDING_COST = 20L;

    /** 회의 요약 재생성 차감량 (최초 생성은 무료). */
    public static final long SUMMARY_REGENERATE_COST = 3L;

    /** 실시간 AI 채팅 메시지 1건당 차감량. */
    public static final long AI_CHAT_MESSAGE_COST = 1L;

    /** 분석 리포트 작성 1회당 차감량 (HTML 오류로 인한 재생성은 무료). */
    public static final long ANALYSIS_REPORT_COST = 5L;
}
