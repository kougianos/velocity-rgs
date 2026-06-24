package com.velocity.rgs.qa.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.blackjack.domain.BlackjackRound;
import com.velocity.rgs.blackjack.persistence.BlackjackRoundRepository;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.config.PlayerContext;
import com.velocity.rgs.roulette.domain.RouletteRound;
import com.velocity.rgs.roulette.persistence.RouletteRoundRepository;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.persistence.SessionCache;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.wallet.domain.WalletBalance;
import com.velocity.rgs.wallet.persistence.WalletBalanceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Demo-only admin endpoints for manual QA (M7 Task 7.4, Appendix A.20):
 * <ul>
 *   <li>{@code POST /api/v1/admin/wallet/balance} — upsert player balance</li>
 *   <li>{@code GET  /api/v1/admin/session/{playerId}} — inspect persistent + cached session</li>
 *   <li>{@code GET  /api/v1/admin/round/{roundId}} — inspect persisted round payload</li>
 *   <li>{@code GET  /api/v1/admin/rounds/{playerId}} — list a player's round history (most recent first)</li>
 * </ul>
 * All endpoints require the {@code ADMIN} role claim and write audit log entries.
 */
@Slf4j
@ConditionalOnProperty(prefix = "rgs", name = "mode", havingValue = "demo", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminQaController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final PlayerContext playerContext;
    private final WalletBalanceRepository walletBalanceRepository;
    private final SessionStore sessionStore;
    private final SessionCache sessionCache;
    private final GameRoundRepository gameRoundRepository;
    private final RouletteRoundRepository rouletteRoundRepository;
    private final BlackjackRoundRepository blackjackRoundRepository;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ wallet/balance

    @PostMapping("/wallet/balance")
    @Transactional
    public ResponseEntity<SetBalanceResponse> setBalance(@Valid @RequestBody SetBalanceRequest req) {
        requireAdmin();
        Money money = Money.of(req.balance(), req.currency());
        Instant now = Instant.now();
        WalletBalance row = walletBalanceRepository.findById(req.playerId())
                .map(existing -> {
                    if (!existing.getCurrency().equals(req.currency())) {
                        throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                                "Existing wallet currency " + existing.getCurrency()
                                        + " cannot be overridden to " + req.currency());
                    }
                    existing.setBalance(money.amount());
                    existing.setBalanceMinor(money.toMinor());
                    existing.setUpdatedAt(now);
                    return existing;
                })
                .orElseGet(() -> WalletBalance.builder()
                        .playerId(req.playerId())
                        .currency(req.currency())
                        .balance(money.amount())
                        .balanceMinor(money.toMinor())
                        .updatedAt(now)
                        .build());
        WalletBalance saved = walletBalanceRepository.saveAndFlush(row);
        log.info("ADMIN setBalance admin={} playerId={} currency={} balance={}",
                playerContext.getPlayerId(), saved.getPlayerId(), saved.getCurrency(), saved.getBalance());
        return ResponseEntity.ok(new SetBalanceResponse(
                saved.getPlayerId(), saved.getCurrency(), saved.getBalance(), saved.getVersion(), saved.getUpdatedAt()));
    }

    // ------------------------------------------------------------------ session

    @GetMapping("/session/{playerId}")
    public ResponseEntity<SessionInspection> inspectSession(@PathVariable String playerId) {
        requireAdmin();
        GameSession persisted = sessionStore.findByPlayerId(playerId)
                .orElseThrow(() -> new RgsException(ErrorCode.SESSION_NOT_FOUND,
                        "No session for player: " + playerId));
        boolean cached = sessionCache.isCached(playerId);
        log.info("ADMIN inspectSession admin={} playerId={} sessionId={} cached={}",
                playerContext.getPlayerId(), playerId, persisted.getSessionId(), cached);
        return ResponseEntity.ok(SessionInspection.from(persisted, cached, objectMapper));
    }

    // ------------------------------------------------------------------ round

    @GetMapping("/round/{roundId}")
    public ResponseEntity<RoundInspection> inspectRound(@PathVariable String roundId) {
        requireAdmin();
        GameRound round = gameRoundRepository.findByRoundId(roundId)
                .orElseThrow(() -> new RgsException(ErrorCode.SESSION_NOT_FOUND,
                        "Round not found: " + roundId));
        log.info("ADMIN inspectRound admin={} roundId={} playerId={}",
                playerContext.getPlayerId(), roundId, round.getPlayerId());
        return ResponseEntity.ok(RoundInspection.from(round, objectMapper));
    }

    /**
     * Lists a player's persisted rounds across <b>all game types</b>, most recent first (capped at 200), as
     * lightweight summaries for the History page. Slot ({@code game_round}) and roulette
     * ({@code roulette_round}) rounds are merged and re-sorted by time so the page shows one unified ledger.
     * The heavy per-round payload (matrix, rng draws …) stays behind {@code GET /round/{roundId}}.
     */
    @GetMapping("/rounds/{playerId}")
    public ResponseEntity<List<RoundSummary>> listRounds(@PathVariable String playerId) {
        requireAdmin();
        Stream<RoundSummary> slotRounds = gameRoundRepository.findByPlayerIdOrderByCreatedAtDesc(playerId)
                .stream().map(RoundSummary::from);
        Stream<RoundSummary> rouletteRounds = rouletteRoundRepository.findByPlayerIdOrderByCreatedAtDesc(playerId)
                .stream().map(RoundSummary::fromRoulette);
        Stream<RoundSummary> blackjackRounds = blackjackRoundRepository.findByPlayerIdOrderByCreatedAtDesc(playerId)
                .stream().map(RoundSummary::fromBlackjack);
        List<RoundSummary> rounds = Stream.concat(Stream.concat(slotRounds, rouletteRounds), blackjackRounds)
                .sorted(Comparator.comparing(RoundSummary::createdAt).reversed())
                .limit(200)
                .toList();
        log.info("ADMIN listRounds admin={} playerId={} count={}",
                playerContext.getPlayerId(), playerId, rounds.size());
        return ResponseEntity.ok(rounds);
    }

    // ------------------------------------------------------------------ helpers

    private void requireAdmin() {
        if (!playerContext.hasRole(ADMIN_ROLE)) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Admin role required for QA admin endpoints");
        }
    }

    // ------------------------------------------------------------------ DTOs

    public record SetBalanceRequest(
            @NotBlank String playerId,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Positive BigDecimal balance
    ) {}

    public record SetBalanceResponse(
            String playerId,
            String currency,
            BigDecimal balance,
            long version,
            Instant updatedAt
    ) {}

    public record SessionInspection(
            String sessionId,
            String playerId,
            String gameId,
            String mathVersion,
            String currency,
            String currentState,
            BigDecimal currentBet,
            int remainingFreeSpins,
            BigDecimal accumulatedFreeSpinsWin,
            String nextActionAllowed,
            long sessionVersion,
            Instant createdAt,
            Instant updatedAt,
            boolean cachedInRedis,
            Object activeFeaturePayload
    ) {
        static SessionInspection from(GameSession s, boolean cached, ObjectMapper mapper) {
            Object payload = parseJson(s.getActiveFeaturePayload(), mapper);
            return new SessionInspection(
                    s.getSessionId(), s.getPlayerId(), s.getGameId(), s.getMathVersion(),
                    s.getCurrency(),
                    s.getCurrentState() != null ? s.getCurrentState().name() : null,
                    s.getCurrentBet(), s.getRemainingFreeSpins(), s.getAccumulatedFreeSpinsWin(),
                    s.getNextActionAllowed(), s.getSessionVersion(), s.getCreatedAt(), s.getUpdatedAt(),
                    cached, payload);
        }
    }

    public record RoundInspection(
            String roundId,
            String sessionId,
            String playerId,
            String gameId,
            String mathVersion,
            String stateContext,
            BigDecimal betAmount,
            BigDecimal totalWin,
            String currency,
            boolean powerBetActive,
            String betTransactionId,
            String winTransactionId,
            Instant createdAt,
            Object matrix,
            Object stopPositions,
            Object rngDraws,
            Object winLines,
            Object reasonCodes
    ) {
        static RoundInspection from(GameRound r, ObjectMapper mapper) {
            return new RoundInspection(
                    r.getRoundId(), r.getSessionId(), r.getPlayerId(), r.getGameId(), r.getMathVersion(),
                    r.getStateContext() != null ? r.getStateContext().name() : null,
                    r.getBetAmount(), r.getTotalWin(), r.getCurrency(),
                    r.isPowerBetActive(), r.getBetTransactionId(), r.getWinTransactionId(), r.getCreatedAt(),
                    parseJson(r.getMatrix(), mapper),
                    parseJson(r.getStopPositions(), mapper),
                    parseJson(r.getRngDraws(), mapper),
                    parseJson(r.getWinLines(), mapper),
                    parseJson(r.getReasonCodes(), mapper));
        }
    }

    public record RoundSummary(
            String roundId,
            String gameId,
            String stateContext,
            BigDecimal betAmount,
            BigDecimal totalWin,
            String currency,
            boolean powerBetActive,
            Instant createdAt
    ) {
        static RoundSummary from(GameRound r) {
            return new RoundSummary(
                    r.getRoundId(), r.getGameId(),
                    r.getStateContext() != null ? r.getStateContext().name() : null,
                    r.getBetAmount(), r.getTotalWin(), r.getCurrency(),
                    r.isPowerBetActive(), r.getCreatedAt());
        }

        static RoundSummary fromRoulette(RouletteRound r) {
            return new RoundSummary(
                    r.getRoundId(), r.getGameId(), "ROULETTE",
                    r.getTotalBet(), r.getTotalWin(), r.getCurrency(),
                    false, r.getCreatedAt());
        }

        static RoundSummary fromBlackjack(BlackjackRound r) {
            return new RoundSummary(
                    r.getRoundId(), r.getGameId(), "BLACKJACK",
                    r.getTotalBet(), r.getTotalWin(), r.getCurrency(),
                    false, r.getCreatedAt());
        }
    }

    private static Object parseJson(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            return Map.of("raw", raw, "parseError", ex.getMessage());
        }
    }
}
