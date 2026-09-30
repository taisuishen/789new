package com.bingo789.lobby.web.dto;

import com.bingo789.lobby.entity.GameStatus;
import jakarta.validation.constraints.NotNull;

public record GameStatusRequest(@NotNull GameStatus status) {
}
