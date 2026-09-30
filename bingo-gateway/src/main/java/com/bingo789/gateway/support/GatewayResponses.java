package com.bingo789.gateway.support;

import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.ErrorCode;
import com.bingo789.common.core.HeaderNames;
import com.bingo789.common.core.Result;
import com.bingo789.common.core.json.JsonUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Bodies in the same {@link Result} shape the downstream services return. */
public final class GatewayResponses {

    /** Exchange attribute holding the trace id chosen by TraceIdGlobalFilter. */
    public static final String TRACE_ID_ATTR = "bingo.traceId";

    private GatewayResponses() {
    }

    public static Mono<Void> error(ServerWebExchange exchange, ErrorCode code) {
        return write(exchange, HttpStatusCode.valueOf(code.httpStatus()), code, code.message());
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code, String message) {
        return write(exchange, status, code, message, null);
    }

    /** Success answer for endpoints the gateway serves itself. */
    public static <T> Mono<Void> ok(ServerWebExchange exchange, T data) {
        return write(exchange, HttpStatus.OK, CommonErrorCode.SUCCESS, CommonErrorCode.SUCCESS.message(), data);
    }

    public static <T> Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code,
                                       String message, T data) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Result<T> body = new Result<>(code.code(), message, data, traceId(exchange));
        DataBuffer buffer = response.bufferFactory().wrap(JsonUtils.toJsonBytes(body));
        return response.writeWith(Mono.just(buffer));
    }

    public static String traceId(ServerWebExchange exchange) {
        String traceId = exchange.getAttribute(TRACE_ID_ATTR);
        return traceId != null ? traceId : exchange.getRequest().getHeaders().getFirst(HeaderNames.TRACE_ID);
    }
}
