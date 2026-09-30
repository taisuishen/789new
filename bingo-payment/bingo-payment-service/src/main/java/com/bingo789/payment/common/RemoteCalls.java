package com.bingo789.payment.common;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Wraps read-only remote calls made while serving a player request (compliance gates, limits), so a failing
 * dependency surfaces as 503 instead of 500. Never use it for money movements: those need explicit handling of
 * the "unknown outcome" case.
 */
@Slf4j
public final class RemoteCalls {

    private RemoteCalls() {
    }

    public static <T> T call(String name, Supplier<T> call) {
        try {
            return call.get();
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("remote call {} failed", name, e);
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
