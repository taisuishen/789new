package com.bingo789.risk.common;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.HeaderNames;

/**
 * Back-office operator identity. The back-office gateway authenticates the operator and sets {@link #HEADER};
 * this service is not reachable from outside the cluster.
 * TODO: RBAC checks per endpoint once the back-office gateway exists.
 */
public final class Operators {

    public static final String HEADER = HeaderNames.OPERATOR_ID;

    private Operators() {
    }

    public static String require(String operator) {
        if (!Texts.hasText(operator)) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED, "operator identity missing");
        }
        return Texts.truncate(operator.trim(), 64);
    }
}
