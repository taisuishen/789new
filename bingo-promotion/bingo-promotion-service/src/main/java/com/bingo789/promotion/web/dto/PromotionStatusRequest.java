package com.bingo789.promotion.web.dto;

/**
 * @param status  ONLINE or OFFLINE (a promotion never returns to DRAFT)
 * @param version the version the operator reviewed: putting a promotion ONLINE approves exactly that version
 */
public record PromotionStatusRequest(String status, Integer version) {
}
