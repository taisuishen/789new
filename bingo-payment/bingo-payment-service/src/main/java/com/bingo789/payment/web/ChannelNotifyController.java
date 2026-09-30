package com.bingo789.payment.web;

import com.bingo789.payment.channel.ChannelRegistry;
import com.bingo789.payment.channel.DepositResult;
import com.bingo789.payment.channel.InvalidNotifyException;
import com.bingo789.payment.channel.PaymentChannel;
import com.bingo789.payment.channel.PayoutResult;
import com.bingo789.payment.service.DepositService;
import com.bingo789.payment.service.WithdrawService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Asynchronous results from payment providers. Served only through the dedicated callback ingress (like the game
 * provider callbacks: provider IP allow-lists, no player session) and never routed by the player gateway.
 * Nothing is processed before the adapter has verified the signature.
 */
@Slf4j
@RestController
@RequestMapping("/notify/{channelCode}")
@RequiredArgsConstructor
public class ChannelNotifyController {

    private final ChannelRegistry channelRegistry;
    private final DepositService depositService;
    private final WithdrawService withdrawService;

    @PostMapping("/deposit")
    public ResponseEntity<String> deposit(@PathVariable("channelCode") String channelCode, HttpServletRequest request)
            throws IOException {
        PaymentChannel channel = channelRegistry.find(channelCode).orElse(null);
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("unknown channel");
        }
        String body = rawBody(request);
        DepositResult result;
        try {
            result = channel.parseDepositNotify(headers(request), body);
        } catch (InvalidNotifyException e) {
            log.warn("ALERT rejected deposit notify from {} ({}): {}", channelCode, request.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(channel.notifyAckBody(false));
        }
        boolean accepted;
        try {
            accepted = depositService.onNotify(channelCode, result);
        } catch (RuntimeException e) {
            // e.g. wallet timeout: outcome unknown, let the channel re-notify (the credit is idempotent)
            log.warn("deposit notify {} from {} not processed, channel will retry", result.orderNo(), channelCode, e);
            accepted = false;
        }
        return ack(channel, accepted);
    }

    @PostMapping("/payout")
    public ResponseEntity<String> payout(@PathVariable("channelCode") String channelCode, HttpServletRequest request)
            throws IOException {
        PaymentChannel channel = channelRegistry.find(channelCode).orElse(null);
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("unknown channel");
        }
        String body = rawBody(request);
        PayoutResult result;
        try {
            result = channel.parsePayoutNotify(headers(request), body);
        } catch (InvalidNotifyException e) {
            log.warn("ALERT rejected payout notify from {} ({}): {}", channelCode, request.getRemoteAddr(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(channel.notifyAckBody(false));
        }
        boolean accepted;
        try {
            accepted = withdrawService.onPayoutNotify(channelCode, result);
        } catch (RuntimeException e) {
            log.warn("payout notify {} from {} not processed, channel will retry", result.orderNo(), channelCode, e);
            accepted = false;
        }
        return ack(channel, accepted);
    }

    private static ResponseEntity<String> ack(PaymentChannel channel, boolean accepted) {
        return accepted
                ? ResponseEntity.ok(channel.notifyAckBody(true))
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(channel.notifyAckBody(false));
    }

    /** Read the exact bytes: signatures are computed over the raw body, not over a re-serialized form. */
    private static String rawBody(HttpServletRequest request) throws IOException {
        return StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
