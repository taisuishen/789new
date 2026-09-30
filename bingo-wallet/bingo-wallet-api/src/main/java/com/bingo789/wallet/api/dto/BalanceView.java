package com.bingo789.wallet.api.dto;

import com.bingo789.wallet.api.enums.WalletStatus;

import java.math.BigDecimal;

public record BalanceView(long userId, String currency, BigDecimal balance, BigDecimal frozen, WalletStatus status) {
}
