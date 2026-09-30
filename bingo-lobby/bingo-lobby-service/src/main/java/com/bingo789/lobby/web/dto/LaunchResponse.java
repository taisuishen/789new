package com.bingo789.lobby.web.dto;

/** @param walletMode SEAMLESS or TRANSFER */
public record LaunchResponse(String url, String walletMode) {
}
