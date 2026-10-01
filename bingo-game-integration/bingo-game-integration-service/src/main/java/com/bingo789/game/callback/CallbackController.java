package com.bingo789.game.callback;

import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.config.GameIntegrationProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Single entry point for all provider callbacks: {@code /callback/{provider}/{action...}}. The action is the rest of
 * the path after the provider code and may have several segments or a suffix ({@code transaction/game/bet},
 * {@code Cash/TransferInOut}, {@code bet.html}); it is empty for providers that post every call to one URL and name
 * the action inside the (encrypted) body. Adapters interpret it.
 * Served only on the dedicated callback domain / ELB / node pool, physically isolated from player traffic,
 * so that player-side floods or attacks cannot disturb in-flight bets and payouts.
 */
@RestController
@RequiredArgsConstructor
public class CallbackController {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final CallbackDispatcher dispatcher;
    private final GameIntegrationProperties properties;

    @RequestMapping(value = {"/callback/{provider}", "/callback/{provider}/**"}, method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<byte[]> callback(@PathVariable("provider") String provider, HttpServletRequest http) throws IOException {
        String action = actionOf(http.getRequestURI(), provider);
        // read the raw stream ourselves: signatures cover the exact bytes, and Spring would rebuild form bodies
        byte[] body = http.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }
        CallbackRequest request = new CallbackRequest(provider, action, headers(http), http.getQueryString(), body,
                clientIp(http, properties.callback().trustedProxyHops()), Instant.now());
        CallbackResponse response = dispatcher.dispatch(request);
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }

    /** Everything after {@code /callback/<provider>/}, as received (still URL-encoded). */
    static String actionOf(String requestUri, String provider) {
        String prefix = "/callback/" + provider;
        int start = requestUri.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        String rest = requestUri.substring(start + prefix.length());
        return rest.startsWith("/") ? rest.substring(1) : rest;
    }

    private static Map<String, String> headers(HttpServletRequest http) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(http.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), http.getHeader(name));
        }
        return headers;
    }

    /**
     * The address our outermost trusted proxy saw. Entries to the left of it in X-Forwarded-For are
     * client-controlled and must not be trusted for IP allow-listing.
     */
    static String clientIp(HttpServletRequest http, int trustedHops) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || trustedHops <= 0) {
            return http.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        int index = Math.max(0, hops.length - trustedHops);
        return hops[index].strip();
    }
}
