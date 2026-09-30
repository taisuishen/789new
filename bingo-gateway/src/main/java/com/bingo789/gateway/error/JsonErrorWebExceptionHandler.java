package com.bingo789.gateway.error;

import com.bingo789.common.core.CommonErrorCode;
import com.bingo789.common.core.ErrorCode;
import com.bingo789.gateway.support.GatewayResponses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * Renders gateway-level failures (no route, no instance, downstream unreachable, timeouts) as {@code Result}
 * JSON instead of Boot's default error body. Ordered before Boot's handler (-1). Exception messages are never
 * echoed to the client.
 */
@Slf4j
@Component
public class JsonErrorWebExceptionHandler implements WebExceptionHandler, Ordered {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatusCode status = statusOf(ex);
        if (status.is5xxServerError()) {
            if (ex instanceof ResponseStatusException || ex instanceof ConnectException) {
                log.warn("{} {} failed: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex.toString());
            } else {
                log.error("{} {} failed", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex);
            }
        }
        ErrorCode code = errorCodeOf(status);
        return GatewayResponses.write(exchange, status, code, code.message());
    }

    private static HttpStatusCode statusOf(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return rse.getStatusCode();
        }
        if (ex instanceof ConnectException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (ex instanceof TimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static ErrorCode errorCodeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> CommonErrorCode.BAD_REQUEST;
            case 401 -> CommonErrorCode.UNAUTHORIZED;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 429 -> CommonErrorCode.TOO_MANY_REQUESTS;
            case 451 -> CommonErrorCode.REGION_NOT_ALLOWED;
            case 502, 503, 504 -> CommonErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? CommonErrorCode.BAD_REQUEST : CommonErrorCode.SYSTEM_ERROR;
        };
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
