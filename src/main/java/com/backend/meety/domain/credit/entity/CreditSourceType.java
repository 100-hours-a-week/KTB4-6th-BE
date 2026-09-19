package com.backend.meety.domain.credit.entity;

/**
 * 크레딧 거래의 원본 종류. 값은 크레딧 원장 조회 응답(8.2)의 sourceType 계약을 그대로 따른다.
 */
public enum CreditSourceType {
    AI_CHAT,
    AI_SUMMARY,
    AI_REPORT,
    RECORDING,
    SCHEDULE,
    TEAM_CREATE
}
