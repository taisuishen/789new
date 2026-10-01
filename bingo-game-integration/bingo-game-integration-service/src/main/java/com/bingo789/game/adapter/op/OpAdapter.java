package com.bingo789.game.adapter.op;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.game.adapter.ProviderAdapter;
import com.bingo789.game.adapter.model.CallbackError;
import com.bingo789.game.adapter.model.CallbackException;
import com.bingo789.game.adapter.model.CallbackRequest;
import com.bingo789.game.adapter.model.CallbackResponse;
import com.bingo789.game.adapter.model.CommandOutcome;
import com.bingo789.game.adapter.model.RoundStatus;
import com.bingo789.game.adapter.model.WalletCommand;
import com.bingo789.game.adapter.support.Ciphers;
import com.bingo789.game.adapter.support.Fields;
import com.bingo789.game.adapter.support.Forms;
import com.bingo789.game.api.dto.BetPullPage;
import com.bingo789.game.api.dto.BetPullQuery;
import com.bingo789.game.api.dto.LaunchCommand;
import com.bingo789.game.api.dto.LaunchView;
import com.bingo789.game.api.dto.ProviderBetRecordView;
import com.bingo789.game.api.dto.ProviderGameView;
import com.bingo789.game.provider.ProviderClient;
import com.bingo789.game.wallet.PlayerIds;
import com.bingo789.wallet.api.enums.TxnType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OMNIPLAY (OP, jigaming777) seamless wallet. Every callback is {@code POST /callback/OP} (one URL, empty action),
 * form {@code dc=<domain code>&x=<URL-safe Base64 of AES-CBC(JSON)>}; the JSON's {@code action} names the call:
 * 1 bet, 2 cancel bet, 3 balance, 4 game result, 11 campaign payoff. Replies are plain JSON, always HTTP 200,
 * {@code status} "0000" = success. Outbound calls use the same envelope against one vendor URL.
 * <p>
 * Auth: encryption is the authentication (vendor Java sample): AES/CBC/NoPadding, key and IV = the raw bytes of the
 * configured strings, plaintext space-padded to 16 bytes, URL-safe Base64 without padding (either alphabet accepted
 * inbound). {@link #parse} decrypts (failure = AUTH_FAILED) and checks {@code ts} (epoch ms) with
 * {@link Fields#checkTimestamp}; {@link #verifySignature} is empty. {@code dc} is ignored inbound.
 * <p>
 * Ids: {@code transferId} is unique per transaction and is the wallet txn id (bet and result carry different ones);
 * the round is {@code gType-gameSeqNo} (gameSeqNo is unique only within a game type). Cancel (action 2) names the
 * bet's transferId only; our rollback id is {@code cancel:<transferId>}. A result of a free round
 * ({@code gameMode != 0} or a {@code freeCardId}) is a FREE_PAYOUT; a campaign payoff is a PROMO_PAYOUT in its own
 * round {@code promo:<transferId>}. Amounts are decimals; {@code bet} is negative (its absolute value is debited),
 * {@code jackpotContribute} is not debited (the vendor's netWin excludes it), {@code win} includes jackpot wins.
 * Currency is the account's (first configured) currency: cancels carry none, and the old code ignored it too.
 * Duplicates: a repeated bet is 9011 with the balance (OP's duplicate code), every other repeat is 0000; cancelling a
 * bet we never received is 0000 (a tombstone refuses the late bet), otherwise OP retries every 10 s forever.
 * <p>
 * Deliberate deviations from the old code: one key set per provider code (the old controller tried every OP
 * firm_config until one decrypted: a second merchant is another provider code); the duplicate check is durable and
 * comes before the balance check (a retried bet used to get 6006 when the balance had dropped meanwhile); a bet of 0
 * (free-card round) is accepted (old: 8000); a cancel after its round was paid refunds the bet (the old code answered
 * 0000 without a refund, see gaps); a cancel before its bet refuses the late bet (old: no-op, late bet accepted);
 * balance failures on action 3 are retryable 6001 (old: terminal 9999); balances are rounded DOWN (old: HALF_DOWN);
 * the bet history is keyed {@code gType-seqNo} (old bug: seqNo only) and a failed window fails the run so it is pulled
 * again (old: skipped, leaving gaps).
 * <p>
 * A cancel after the round was paid is acknowledged (0000) without a refund, as before (the wallet refuses to refund a
 * paid-out bet: BET_SETTLED). Framework gaps: {@link #renderError} has
 * neither the reason nor the keys, so a stale {@code ts} renders 9006 instead of 9005 and a missing action 8000 instead
 * of 8001; the jackpot part of {@code win} is not split into a JACKPOT_PAYOUT; a game is (gType, mType) at OP but
 * LaunchCommand has only a game code, so launches use one configured gType.
 * <p>
 * Configuration ({@code bingo.providers.OP}): {@code operator-id} = parent (agent account), {@code base-url} = vendor
 * API host, {@code secrets.aesKey} / {@code secrets.aesIv} (16 characters each), settings {@code dc} (customer domain
 * code, sent outbound), optional {@code apiPath} (default {@code /apiRequest.do}), {@code gType} (launch game type,
 * default 1 = slots), {@code redirectAllowed}; {@code currencies} = the account currency. Recommended:
 * {@code timestamp-tolerance: 30s} (vendor rule), {@code require-bet-for-payout: false} (the old code paid a result even
 * when it could not link it to its bet, and OP retries a refused result forever), {@code balance-scale: 4} (as the old replies). Bet pull:
 * action 33 allows 5-minute minute-aligned windows at 1 request per second, so
 * {@code bingo.bet-record.pull.providers.OP.page-interval: 1100ms} (the adapter splits the window, one page per
 * 5 minutes); the default 30 min max-window then costs 6 requests per run.
 */
@Slf4j
@Component
public class OpAdapter implements ProviderAdapter {

    static final String NAME = "OP";

    private static final String OK = "0000";
    private static final DateTimeFormatter QUERY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    /** Action 33 accepts at most 5 minutes per query, both ends on a whole minute. */
    private static final Duration QUERY_WINDOW = Duration.ofMinutes(5);

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    /** The body is encrypted with the shared key: {@link #parse} authenticates by decrypting it. */
    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        JsonNode m = open(Forms.body(request).get("x"), client.secret("aesKey"), client.secret("aesIv"));
        long ts = m.path("ts").asLong(0);
        if (ts <= 0) {
            throw CallbackException.auth("ts is missing");
        }
        Fields.checkTimestamp(Instant.ofEpochMilli(ts), request, client);
        JsonNode action = m.path("action");
        if (action.isMissingNode() || action.isNull()) {
            throw CallbackException.badRequest("action cannot be empty");
        }
        return switch (action.asInt(-1)) {
            case 3 -> new WalletCommand.GetBalance(required(m, "uid"), null);
            case 1 -> new WalletCommand.Bet(required(m, "uid"), null, required(m, "transferId"), round(m),
                    text(m, "mType"), amount(m, "bet").abs(), false);
            case 4 -> result(m);
            case 2 -> {
                String bet = required(m, "transferId");
                yield new WalletCommand.Rollback(required(m, "uid"), null, "cancel:" + bet, bet, TxnType.BET, null,
                        text(m, "mType"));
            }
            case 11 -> {
                String id = required(m, "transferId");
                yield new WalletCommand.Payout(required(m, "uid"), null, id, "promo:" + id, null,
                        amount(m, "awardAmount").abs(), TxnType.PROMO_PAYOUT, null, true);
            }
            default -> throw CallbackException.unknownAction(action.asString(""));
        };
    }

    /** Game result: the gross win of the round (0 for a lost round, which closes it). */
    private static WalletCommand result(JsonNode m) {
        BigDecimal win = amount(m, "win");
        if (win.signum() < 0) {
            throw CallbackException.badRequest("win must not be negative");
        }
        boolean freeRound = m.path("gameMode").asInt(0) != 0 || text(m, "freeCardId") != null;
        return new WalletCommand.Payout(required(m, "uid"), null, required(m, "transferId"), round(m), text(m, "mType"),
                win, freeRound ? TxnType.FREE_PAYOUT : TxnType.PAYOUT, null, true);
    }

    private static String round(JsonNode m) {
        return required(m, "gType") + "-" + required(m, "gameSeqNo");
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        String status = switch (outcome.code()) {
            // OP's duplicate code for a repeated bet; repeated results, cancels and payoffs are plain successes
            case SUCCESS -> outcome.replay() && command instanceof WalletCommand.Bet ? "9011" : OK;
            // terminal on purpose: 6001 would make OP retry the bet forever
            case INSUFFICIENT_FUNDS, PLAYER_LOCKED -> "6006";
            case PLAYER_NOT_FOUND, INVALID_TOKEN, TXN_CANCELLED -> "9999";
            // cancel after the round was paid: acknowledged without a refund (OP's documented behaviour)
            case BET_SETTLED -> OK;
            // a result before its bet (only with require-bet-for-payout = true): retry, the bet may still arrive
            case BET_NOT_FOUND -> "6001";
            // nothing to cancel is done: OP would otherwise retry every 10 s forever
            case TXN_NOT_FOUND -> command instanceof WalletCommand.Rollback ? OK : "9015";
            case INVALID_REQUEST -> "8000";
        };
        boolean withBalance = status.equals(OK) || status.equals("9011");
        return reply(status, withBalance ? Fields.balance(outcome.balance(), client.config().balanceScale()) : null);
    }

    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        String status = switch (error) {
            case AUTH_FAILED -> blank(Forms.body(request).get("x")) ? "9004" : "9006";
            case BAD_REQUEST -> "8000";
            case UNKNOWN_ACTION -> "9007";
            case RATE_LIMITED, SYSTEM_RETRYABLE -> "6001";
        };
        return reply(status, null);
    }

    // ================================================================ outbound

    /** Action 27: the game URL of a player (OP creates the account on first launch). */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        if (c.demo()) {
            throw new IllegalArgumentException("OP offers no demo play");
        }
        Map<String, Object> payload = message(27, client);
        payload.put("uid", playerId);
        payload.put("gType", client.setting("gType", "1"));
        payload.put("mType", c.gameCode());
        payload.put("lang", c.language() == null ? "en" : c.language());
        if (c.lobbyUrl() != null) {
            payload.put("lobbyURL", c.lobbyUrl());
        }
        String redirect = client.setting("redirectAllowed", null);
        if (redirect != null) {
            payload.put("isRedirectAllowed", Boolean.parseBoolean(redirect));
        }
        JsonNode response = JsonUtils.mapper().readTree(call(client, payload));
        String url = response.path("path").asString(null);
        if (!OK.equals(response.path("status").asString(null)) || url == null || url.isBlank()) {
            throw new IllegalStateException("OP action 27 failed: " + response.path("status").asString("")
                    + " " + response.path("err_text").asString(""));
        }
        return new LaunchView(url, null);
    }

    /** Action 29: {@code data[]} groups (by game type) with {@code list[] {mType, name}}. */
    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        Map<String, Object> payload = message(29, client);
        payload.put("lang", "en");
        JsonNode response = JsonUtils.mapper().readTree(call(client, payload));
        if (!OK.equals(response.path("status").asString(null))) {
            throw new IllegalStateException("OP action 29 failed: " + response.path("status").asString("")
                    + " " + response.path("err_text").asString(""));
        }
        List<ProviderGameView> games = new ArrayList<>();
        for (JsonNode group : response.path("data")) {
            String category = category(group.path("gType").asString(null));
            for (JsonNode g : group.path("list")) {
                String mType = g.path("mType").asString(null);
                if (mType != null && !mType.isBlank()) {
                    games.add(new ProviderGameView(client.providerCode(), mType, g.path("name").asString(mType),
                            category, null, null, true, true));
                }
            }
        }
        return games;
    }

    /**
     * Action 33 "game history": finished games only, no paging, at most 5 minutes per query on whole minutes. The
     * window is split into 5-minute parts; the cursor is the start of the next part (epoch ms). The tail after the
     * last whole minute of {@code to} is left to the next window (its overlap re-reads it).
     */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        Instant end = query.to().truncatedTo(ChronoUnit.MINUTES);
        Instant start = query.cursor() == null
                ? query.from().truncatedTo(ChronoUnit.MINUTES)
                : Instant.ofEpochMilli(Long.parseLong(query.cursor()));
        if (!start.isBefore(end)) {
            return new BetPullPage(List.of(), null, false);
        }
        Instant partEnd = start.plus(QUERY_WINDOW).isBefore(end) ? start.plus(QUERY_WINDOW) : end;
        Map<String, Object> payload = message(33, client);
        payload.put("starttime", QUERY_TIME.format(start));
        payload.put("endtime", QUERY_TIME.format(partEnd));
        List<ProviderBetRecordView> records = parseHistory(call(client, payload), accountCurrency(client), client.providerCode());
        boolean more = partEnd.isBefore(end);
        return new BetPullPage(records, more ? String.valueOf(partEnd.toEpochMilli()) : null, more);
    }

    /** {@code {"status":"0000","data":[{seqNo, uid, gType, mType, gameDate, reportDate, currency, bet, win ...}]}}. */
    static List<ProviderBetRecordView> parseHistory(String json, String currency, String providerCode) {
        JsonNode response = JsonUtils.mapper().readTree(json);
        String status = response.path("status").asString("");
        if (status.equals("9015")) {
            return List.of(); // "data does not exist"
        }
        if (!OK.equals(status)) {
            throw new IllegalStateException("OP action 33 failed: " + status + " " + response.path("err_text").asString(""));
        }
        List<ProviderBetRecordView> records = new ArrayList<>();
        for (JsonNode r : response.path("data")) {
            String seqNo = r.path("seqNo").asString("");
            String gameType = r.path("gType").asString("");
            Long userId = PlayerIds.tryDecode(r.path("uid").asString(null));
            if (userId == null || seqNo.isBlank() || gameType.isBlank()) {
                log.warn("OP game {}-{} skipped: unknown player {} or incomplete id", gameType, seqNo, r.path("uid").asString(null));
                continue;
            }
            String id = gameType + "-" + seqNo;
            Instant played = time(r.path("gameDate").asString(null));
            Instant settled = time(r.path("reportDate").asString(null));
            if (settled == null) {
                settled = time(r.path("lastModifyTime").asString(null));
            }
            String recordCurrency = r.path("currency").asString("");
            records.add(new ProviderBetRecordView(providerCode, id, id, userId,
                    recordCurrency.isBlank() ? currency : recordCurrency, r.path("mType").asString(null),
                    r.path("bet").asDecimal(BigDecimal.ZERO).abs(), r.path("win").asDecimal(BigDecimal.ZERO), "SETTLED",
                    played, settled != null ? settled : played));
        }
        return records;
    }

    /** OP has no round-status API (action 31 only returns a replay link); OP retries results until they succeed. */
    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ helpers

    /** Encrypts an outbound or test message: space padding, URL-safe Base64 without padding. */
    static String seal(String json, String key, String iv) {
        byte[] sealed = Ciphers.aesEncrypt(Ciphers.utf8(json), Ciphers.utf8(key), Ciphers.utf8(iv), Ciphers.Padding.SPACE);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sealed);
    }

    /** Decrypts {@code x} into its JSON object; anything else is an authentication failure. */
    static JsonNode open(String x, String key, String iv) {
        if (blank(x)) {
            throw CallbackException.auth("x is empty");
        }
        try {
            // either Base64 alphabet; a '+' that form decoding turned into a space is restored first
            String urlSafe = x.strip().replace(' ', '+').replace('+', '-').replace('/', '_');
            byte[] plain = Ciphers.aesDecrypt(Base64.getUrlDecoder().decode(urlSafe), Ciphers.utf8(key), Ciphers.utf8(iv),
                    Ciphers.Padding.SPACE);
            JsonNode node = JsonUtils.mapper().readTree(trimPadding(new String(plain, StandardCharsets.UTF_8)));
            if (node == null || !node.isObject()) {
                throw CallbackException.auth("x is not an encrypted json object");
            }
            return node;
        } catch (IllegalArgumentException e) {
            throw CallbackException.auth("x does not decrypt: " + e.getMessage());
        } catch (JacksonException e) {
            throw CallbackException.auth("x does not decrypt to json");
        }
    }

    /** The vendor pads with spaces; NULs and other trailing whitespace are tolerated as well. */
    private static String trimPadding(String plain) {
        int end = plain.length();
        while (end > 0 && (plain.charAt(end - 1) == '\0' || Character.isWhitespace(plain.charAt(end - 1)))) {
            end--;
        }
        return plain.substring(0, end);
    }

    private static Map<String, Object> message(int action, ProviderClient client) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        // the vendor accepts a message for 30 s only
        payload.put("ts", System.currentTimeMillis());
        payload.put("parent", client.config().operatorId());
        return payload;
    }

    /** One vendor URL for everything: form {@code dc} + {@code x}; the reply is plain JSON. */
    private static String call(ProviderClient client, Map<String, Object> payload) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("dc", client.setting("dc"));
        form.put("x", seal(JsonUtils.toJson(payload), client.secret("aesKey"), client.secret("aesIv")));
        return client.rest().post()
                .uri(client.setting("apiPath", "/apiRequest.do"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(form))
                .retrieve()
                .body(String.class);
    }

    /** ISO-8601 with offset ({@code 2020-08-01T17:45:00.000Z}); without one UTC; unreadable = null (record skipped). */
    private static Instant time(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException unreadable) {
                log.warn("OP history time {} is unreadable", value);
                return null;
            }
        }
    }

    private static String accountCurrency(ProviderClient client) {
        List<String> currencies = client.config().currencies();
        if (currencies.isEmpty()) {
            throw new IllegalStateException("provider " + client.providerCode() + ": currencies must be configured");
        }
        return currencies.getFirst();
    }

    private static String category(String gameType) {
        if (gameType == null) {
            return null;
        }
        return switch (gameType) {
            case "1" -> "Slot";
            case "2" -> "Fishing";
            default -> gameType;
        };
    }

    private static CallbackResponse reply(String status, String balance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (!OK.equals(status)) {
            body.put("err_text", errText(status));
        }
        if (balance != null) {
            body.put("balance", new BigDecimal(balance));
        }
        return CallbackResponse.json(200, JsonUtils.toJson(body));
    }

    private static String errText(String status) {
        return switch (status) {
            case "6001" -> "System busy.";
            case "6006" -> "Your cash balance is not enough.";
            case "8000" -> "The parameter of input error, please check your parameter is correct or not.";
            case "9004" -> "The encrypted data is null or empty.";
            case "9006" -> "Failed to extract the SAML parameters from the encrypted data.";
            case "9007" -> "Unknown action.";
            case "9011" -> "Duplicate transactions.";
            case "9015" -> "Data does not exist.";
            default -> "Failed.";
        };
    }

    private static String text(JsonNode m, String field) {
        String value = m.path(field).asString(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(JsonNode m, String field) {
        return Fields.required(text(m, field), field);
    }

    private static BigDecimal amount(JsonNode m, String field) {
        JsonNode node = m.path(field);
        BigDecimal value = node.isMissingNode() || node.isNull() ? null : node.asDecimal(null);
        if (value == null) {
            throw CallbackException.badRequest(field + " is required and must be a number");
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
