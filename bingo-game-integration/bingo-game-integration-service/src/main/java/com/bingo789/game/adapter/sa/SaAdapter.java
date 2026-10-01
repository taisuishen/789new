package com.bingo789.game.adapter.sa;

import com.bingo789.common.core.json.JsonUtils;
import com.bingo789.common.core.time.BingoTime;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SA Gaming (live casino) seamless wallet: callbacks {@code POST /callback/SA/<GetUserBalance|PlaceBet|PlayerWin|
 * PlayerLost|PlaceBetCancel|BalanceAdjustment>}, {@code text/plain} body = Base64(DES/CBC/PKCS5(query string)), key and
 * IV = the first 8 bytes of the encrypt key. Replies are XML {@code <RequestResponse>} (username, currency, amount,
 * error), always HTTP 200. Amounts are decimal currency units.
 * <p>
 * Auth: possession of the DES key. {@link #parse} decrypts (a body that does not decrypt is AUTH_FAILED, answered
 * 1006 instead of the old HTTP 500) and {@link #verifySignature} is empty. The body is URL-decoded only when it contains
 * a percent escape, so a raw Base64 body keeps its '+'; plaintext values are taken as sent (SA does not URL-encode them;
 * percent escapes are decoded). No timestamp window: SA's retries resend the original timestamp.
 * <p>
 * Idempotency: PlaceBet {@code txnid}; the round is {@code gameid}. SA sends one PlayerWin / PlayerLost per player and
 * round covering all of the player's bets, keyed {@code gameid:username} (the old code keyed on the first bet's txnid;
 * SA's own payout txnid is not relied on to stay stable across its retries). PlaceBetCancel reverses exactly the bet
 * {@code txn_reverse_id} (the stored stake, not the request amount; before its bet: tombstone, answered 0).
 * Duplicates answer 1005 with the balance, which is what SA expects ("stop resending").
 * <p>
 * BalanceAdjustment: type 1 (reward) is a PROMO_PAYOUT in round {@code promo:<txnid>}. Type 2 (tip to the dealer) is a
 * BET of game code {@value #TIP_GAME} in its own closed round {@code tip:<txnid>}: a debit that must respect the
 * balance (a negative ADJUST would be forced below zero under negative-balance-policy ALLOW), and it lets type 3
 * (cancel tip, SA got no reply in time) reverse exactly that tip. Turnover consumers should exclude game code TIP.
 * <p>
 * Deliberate deviations from the old integration: decrypt errors answer 1006 (not HTTP 500); insufficient funds for a
 * tip answer 1004 (not HTTP 500); the payout needs a live bet in the round without a 1-hour marker window; a tip cancel
 * or bet cancel of an unknown transaction succeeds (tombstone) instead of 1005; balances are rounded DOWN.
 * <p>
 * Gaps: IDR / VND in SA's 1:1000 unit are not supported (no amount scale in ProviderConfig). Tip and promo rounds
 * have no counterpart in SA's bet detail report. SA has no round query and no game catalogue (tables = hosts).
 * <p>
 * Configuration ({@code bingo.providers.SA}): {@code secret} = EncryptKey (DES key, first 8 bytes used),
 * {@code secrets.md5Key}, {@code secrets.secretKey} (outbound "Key" / MD5 signature), {@code base-url} = SA API URL
 * (LoginRequest), settings {@code launchUrl} (game client URL), {@code lobbyCode}, optional {@code recordApiUrl}
 * (bet detail API, default {@code base-url}). Game codes are SA table (host) ids. Bet pull: one unpaged call per
 * window returning settled bets; SA indexes live rounds with some delay, so use
 * {@code bingo.bet-record.pull.providers.SA.settle-delay: 5m}.
 */
@Slf4j
@Component
public class SaAdapter implements ProviderAdapter {

    static final String NAME = "SA";

    static final int OK = 0;
    static final int MEMBER_NOT_EXISTS = 1000;
    static final int CURRENCY_INCORRECT = 1001;
    static final int MEMBER_LOCKED = 1003;
    static final int INSUFFICIENT_POINTS = 1004;
    static final int COMMON_ERROR = 1005;
    static final int DECRYPTION_ERROR = 1006;
    static final int SYSTEM_ERROR = 9999;

    static final String TIP_GAME = "TIP";
    private static final String TIP_ROUND = "tip:";

    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return NAME;
    }

    // ================================================================ inbound

    /** Encrypted body: authenticated by decrypting it in {@link #parse}. */
    @Override
    public void verifySignature(CallbackRequest request, ProviderClient client) {
    }

    @Override
    public WalletCommand parse(CallbackRequest request, ProviderClient client) {
        Map<String, String> p = decrypt(request.body(), client.secret());
        String user = Fields.required(p, "username");
        return switch (request.action()) {
            case "GetUserBalance" -> new WalletCommand.GetBalance(user, Fields.required(p, "currency"));
            case "PlaceBet" -> new WalletCommand.Bet(user, Fields.required(p, "currency"), Fields.required(p, "txnid"),
                    Fields.required(p, "gameid"), p.get("hostid"), Fields.amount(p.get("amount"), "amount"), false);
            case "PlayerWin" -> payout(p, user, Fields.amount(p.get("amount"), "amount"));
            case "PlayerLost" -> payout(p, user, BigDecimal.ZERO);
            case "PlaceBetCancel" -> {
                String bet = Fields.required(p, "txn_reverse_id");
                String cancel = p.get("txnid") == null || p.get("txnid").isBlank() ? "cancel:" + bet : p.get("txnid");
                yield new WalletCommand.Rollback(user, Fields.required(p, "currency"), cancel, bet, TxnType.BET,
                        p.get("gameid"), p.get("hostid"));
            }
            case "BalanceAdjustment" -> adjustment(p, user);
            default -> throw CallbackException.unknownAction(request.action());
        };
    }

    /** The round's whole result for this player (0 for PlayerLost), closing the round. */
    private static WalletCommand payout(Map<String, String> p, String user, BigDecimal amount) {
        String round = Fields.required(p, "gameid");
        return new WalletCommand.Payout(user, Fields.required(p, "currency"), round + ":" + user, round, p.get("hostid"),
                amount, TxnType.PAYOUT, null, true);
    }

    private static WalletCommand adjustment(Map<String, String> p, String user) {
        String currency = Fields.required(p, "currency");
        String txnId = Fields.required(p, "txnid");
        BigDecimal amount = Fields.amount(p.get("amount"), "amount");
        return switch (Fields.required(p, "adjustmenttype").strip()) {
            case "1" -> new WalletCommand.Payout(user, currency, txnId, "promo:" + txnId, null, amount,
                    TxnType.PROMO_PAYOUT, null, true);
            case "2" -> new WalletCommand.Bet(user, currency, txnId, TIP_ROUND + txnId, TIP_GAME, amount, true);
            case "3" -> {
                String tip = Fields.required(json(p.get("adjustmentdetails")).path("canceltxnid").asString(null), "canceltxnid");
                yield new WalletCommand.Rollback(user, currency, txnId, tip, TxnType.BET, TIP_ROUND + tip, TIP_GAME);
            }
            default -> throw CallbackException.badRequest("unknown adjustmenttype " + p.get("adjustmenttype"));
        };
    }

    @Override
    public CallbackResponse render(CallbackRequest request, WalletCommand command, CommandOutcome outcome, ProviderClient client) {
        int error = switch (outcome.code()) {
            // SA wants 1005 for a duplicate: it stops resending
            case SUCCESS -> outcome.replay() ? COMMON_ERROR : OK;
            case INSUFFICIENT_FUNDS -> INSUFFICIENT_POINTS;
            case PLAYER_NOT_FOUND, INVALID_TOKEN -> MEMBER_NOT_EXISTS;
            case PLAYER_LOCKED -> MEMBER_LOCKED;
            case BET_NOT_FOUND, TXN_NOT_FOUND, TXN_CANCELLED, BET_SETTLED -> COMMON_ERROR;
            case INVALID_REQUEST -> command != null && !currencyEnabled(client, command.currency()) ? CURRENCY_INCORRECT : COMMON_ERROR;
        };
        return reply(error, outcome.playerId(), outcome.currency(), Fields.balance(outcome.balance(), client.config().balanceScale()));
    }

    /** 9999 = SA's system error: SA retries payouts / cancels and cancels an unanswered bet. */
    @Override
    public CallbackResponse renderError(CallbackRequest request, CallbackError error) {
        int code = switch (error) {
            case AUTH_FAILED -> DECRYPTION_ERROR;
            case BAD_REQUEST, UNKNOWN_ACTION -> COMMON_ERROR;
            case RATE_LIMITED, SYSTEM_RETRYABLE -> SYSTEM_ERROR;
        };
        return reply(code, null, null, null);
    }

    // ================================================================ outbound

    /** Server-to-server LoginRequest (SA's own session token), then the game client URL. */
    @Override
    public LaunchView launch(LaunchCommand c, String playerId, ProviderClient client) {
        String time = now();
        String key = client.secret("secretKey");
        String query = c.demo()
                ? "method=LoginRequestForFun&Key=" + key + "&Time=" + time + "&amount=1000000&CurrencyType=" + c.currency()
                : "method=LoginRequest&Key=" + key + "&Time=" + time + "&Username=" + playerId + "&CurrencyType=" + c.currency();
        Element response = xml(call(client, client.config().baseUrl(), query, time));
        requireOk(response, "LoginRequest");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("username", c.demo() ? text(response, "DisplayName") : playerId);
        params.put("token", text(response, "Token"));
        params.put("lobby", client.setting("lobbyCode"));
        params.put("lang", language(c.language()));
        params.put("mobile", String.valueOf("MOBILE".equalsIgnoreCase(c.platform())));
        if (c.lobbyUrl() != null) {
            params.put("returnurl", c.lobbyUrl());
        }
        if (c.gameCode() != null && !c.gameCode().isBlank()) {
            params.put("options", "defaulttable=" + c.gameCode());
        }
        return new LaunchView(client.setting("launchUrl") + "?" + Forms.encode(params), null);
    }

    /** SA has no game catalogue API (only active hosts / tables); the lobby catalogue is maintained manually. */
    @Override
    public List<ProviderGameView> listGames(ProviderClient client) {
        return List.of();
    }

    /** GetAllBetDetailsForTimeIntervalDV: settled bets of the window, one unpaged call (times in UTC+8). */
    @Override
    public BetPullPage pullBetRecords(BetPullQuery query, ProviderClient client) {
        String time = now();
        String q = "method=GetAllBetDetailsForTimeIntervalDV&Key=" + client.secret("secretKey") + "&Time=" + time
                + "&FromTime=" + QUERY_TIME.format(query.from().atOffset(BingoTime.ZONE))
                + "&ToTime=" + QUERY_TIME.format(query.to().atOffset(BingoTime.ZONE));
        String response = call(client, client.setting("recordApiUrl", client.config().baseUrl()), q, time);
        String currency = client.config().currencies().isEmpty() ? null : client.config().currencies().getFirst();
        return new BetPullPage(parseBetDetails(response, client.providerCode(), currency), null, false);
    }

    /**
     * One record per bet ({@code BetID}) in round {@code GameID}; {@code ResultAmount} is the bet's net win/loss, so the
     * payout is {@code BetAmount + ResultAmount}. The game code is the table ({@code HostID}), as in the callbacks.
     */
    static List<ProviderBetRecordView> parseBetDetails(String response, String providerCode, String currency) {
        Element root = xml(response);
        requireOk(root, "GetAllBetDetailsForTimeIntervalDV");
        List<ProviderBetRecordView> records = new ArrayList<>();
        NodeList bets = root.getElementsByTagName("BetDetail");
        for (int i = 0; i < bets.getLength(); i++) {
            Element bet = (Element) bets.item(i);
            String username = text(bet, "Username");
            Long userId = PlayerIds.tryDecode(username);
            if (userId == null) {
                log.warn("SA bet {} belongs to unknown player {}", text(bet, "BetID"), username);
                continue;
            }
            BigDecimal stake = decimal(bet, "BetAmount");
            records.add(new ProviderBetRecordView(providerCode, text(bet, "BetID"), text(bet, "GameID"), userId, currency,
                    text(bet, "HostID"), stake, stake.add(decimal(bet, "ResultAmount")), "SETTLED",
                    time(text(bet, "BetTime")), time(text(bet, "PayoutTime"))));
        }
        return records;
    }

    @Override
    public RoundStatus queryRound(String roundId, String playerId, String currency, ProviderClient client) {
        return RoundStatus.unknown();
    }

    // ================================================================ crypto

    /** Base64(DES/CBC/PKCS5(plain)), key and IV = the first 8 bytes of the encrypt key. */
    static String encrypt(String plain, String key) {
        byte[] key8 = key8(key);
        return Base64.getEncoder().encodeToString(Ciphers.desEncrypt(Ciphers.utf8(plain), key8, key8, Ciphers.Padding.PKCS5));
    }

    /** Decrypts a callback body into its parameters (lower-cased names); anything that does not decrypt is AUTH_FAILED. */
    static Map<String, String> decrypt(byte[] body, String key) {
        String sealed = new String(body, StandardCharsets.US_ASCII).strip();
        if (sealed.isEmpty()) {
            throw CallbackException.auth("empty body");
        }
        try {
            if (sealed.indexOf('%') >= 0) {
                sealed = Forms.decode(sealed);
            }
            byte[] key8 = key8(key);
            byte[] plain = Ciphers.desDecrypt(Base64.getMimeDecoder().decode(sealed), key8, key8, Ciphers.Padding.PKCS5);
            Map<String, String> params = plainQuery(new String(plain, StandardCharsets.UTF_8));
            if (params.isEmpty()) {
                throw CallbackException.auth("decrypted body is not a query string");
            }
            return params;
        } catch (IllegalArgumentException e) {
            throw CallbackException.auth("body does not decrypt");
        }
    }

    /** {@code name=value&...} as SA sends it inside the ciphertext: values unencoded, percent escapes decoded. */
    private static Map<String, String> plainQuery(String plain) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : plain.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = pair.substring(eq + 1);
            params.putIfAbsent(pair.substring(0, eq).strip().toLowerCase(Locale.ROOT),
                    value.indexOf('%') >= 0 ? Forms.decode(value) : value);
        }
        return params;
    }

    private static byte[] key8(String key) {
        return Arrays.copyOf(key.getBytes(StandardCharsets.US_ASCII), 8);
    }

    // ================================================================ helpers

    /** Outbound call: form {@code q} = encrypted query, {@code s} = md5(query + md5Key + Time + secretKey). */
    private static String call(ProviderClient client, String url, String query, String time) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("q", encrypt(query, client.secret()));
        form.put("s", Ciphers.md5Hex(query + client.secret("md5Key") + time + client.secret("secretKey")));
        return client.rest().post()
                .uri(URI.create(url))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Forms.encode(form))
                .retrieve()
                .body(String.class);
    }

    private static String now() {
        return REQUEST_TIME.format(LocalDateTime.now(BingoTime.ZONE));
    }

    private static void requireOk(Element response, String call) {
        String code = text(response, "ErrorMsgId");
        if (!"0".equals(code)) {
            throw new IllegalStateException("SA " + call + " failed: " + code + " " + text(response, "ErrorMsg"));
        }
    }

    /** Parses SA's XML with DOCTYPEs and external entities disabled. */
    private static Element xml(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("SA replied with an empty body");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return document.getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("SA replied with malformed XML: " + e.getMessage(), e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().strip();
    }

    private static BigDecimal decimal(Element parent, String tag) {
        String value = text(parent, tag);
        return value == null || value.isEmpty() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /** SA times are UTC+8 wall clock, e.g. {@code 2021-03-19T16:11:47.191}. */
    private static Instant time(String value) {
        return value == null || value.isEmpty() ? null
                : LocalDateTime.parse(value.replace(' ', 'T')).toInstant(BingoTime.ZONE);
    }

    private static JsonNode json(String value) {
        if (value == null || value.isBlank()) {
            throw CallbackException.badRequest("adjustmentdetails is required");
        }
        try {
            return JsonUtils.mapper().readTree(value);
        } catch (JacksonException e) {
            throw CallbackException.badRequest("adjustmentdetails is not json");
        }
    }

    private static boolean currencyEnabled(ProviderClient client, String currency) {
        return currency == null || client.config().currencies().isEmpty() || client.config().currencies().contains(currency);
    }

    private static CallbackResponse reply(int error, String username, String currency, String amount) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><RequestResponse>");
        element(xml, "username", username);
        element(xml, "currency", currency);
        element(xml, "amount", amount);
        element(xml, "error", String.valueOf(error));
        xml.append("</RequestResponse>");
        return new CallbackResponse(200, "application/xml", xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void element(StringBuilder xml, String name, String value) {
        if (value == null) {
            return;
        }
        xml.append('<').append(name).append('>');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> xml.append("&amp;");
                case '<' -> xml.append("&lt;");
                case '>' -> xml.append("&gt;");
                case '"' -> xml.append("&quot;");
                case '\'' -> xml.append("&apos;");
                default -> xml.append(ch);
            }
        }
        xml.append("</").append(name).append('>');
    }

    private static String language(String language) {
        if (language == null) {
            return "en_US";
        }
        String lang = language.replace('_', '-').toLowerCase(Locale.ROOT);
        if (lang.equals("zh-tw") || lang.equals("zh-hk") || lang.equals("zh-hant")) {
            return "zh_TW";
        }
        return switch (lang.length() > 2 ? lang.substring(0, 2) : lang) {
            case "zh" -> "zh_CN";
            case "th" -> "th";
            case "vi" -> "vn";
            case "ko" -> "ko";
            case "id" -> "id";
            default -> "en_US";
        };
    }
}
