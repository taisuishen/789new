package com.bingo789.game.adapter;

import com.bingo789.game.adapter.model.Transfer;
import com.bingo789.game.provider.ProviderClient;

import java.math.BigDecimal;

/**
 * Transfer-wallet protocol. Requirements to agree with the provider before going live:
 * our orderNo is their idempotency key, and {@link #queryTransfer} can tell "never applied" (NOT_FOUND)
 * apart from "failed" — otherwise lost transfers ("钱卡在厂商") cannot be recovered automatically.
 */
public interface TransferCapable {

    /** Platform -> provider. */
    Transfer.Result transferIn(Transfer.Request request, ProviderClient client);

    /** Provider -> platform. */
    Transfer.Result transferOut(Transfer.Request request, ProviderClient client);

    Transfer.Result queryTransfer(String orderNo, ProviderClient client);

    BigDecimal providerBalance(String playerId, String currency, ProviderClient client);
}
