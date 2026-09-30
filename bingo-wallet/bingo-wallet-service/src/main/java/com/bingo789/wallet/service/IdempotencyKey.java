package com.bingo789.wallet.service;

import com.bingo789.wallet.api.enums.TxnType;

/** Mirrors uk_idempotency (provider_code, provider_txn_id, txn_type) on wallet_txn. */
record IdempotencyKey(String providerCode, String providerTxnId, TxnType type) {
}
