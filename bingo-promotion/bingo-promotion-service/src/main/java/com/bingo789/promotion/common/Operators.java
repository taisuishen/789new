package com.bingo789.promotion.common;

import com.bingo789.common.core.BizException;
import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.HeaderNames;

/**
 * Back-office operator identity. The back-office gateway authenticates the operator and sets {@link #HEADER};
 * this service is not reachable from outside the cluster.
 */
public final class Operators {

    public static final String HEADER = HeaderNames.OPERATOR_ID;

    private Operators() {
    }

    public static String require(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED, "operator identity missing");
        }
        return Texts.truncate(operator.trim(), 64);
    }
}
