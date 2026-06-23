package com.velocity.rgs.roulette.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.roulette.api.RouletteBetRequest;
import com.velocity.rgs.roulette.api.RouletteInitRequest;
import com.velocity.rgs.roulette.api.RouletteInitResponse;
import com.velocity.rgs.roulette.api.RouletteSpinRequest;
import com.velocity.rgs.roulette.api.RouletteSpinResponse;
import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.roulette.config.RouletteCatalogRegistry;
import com.velocity.rgs.roulette.domain.RouletteBetKind;
import com.velocity.rgs.roulette.domain.RouletteRound;
import com.velocity.rgs.roulette.engine.RouletteBet;
import com.velocity.rgs.roulette.engine.RouletteBetResult;
import com.velocity.rgs.roulette.engine.RouletteEvaluation;
import com.velocity.rgs.roulette.engine.RouletteEvaluator;
import com.velocity.rgs.roulette.engine.RouletteSpin;
import com.velocity.rgs.roulette.engine.RouletteWheel;
import com.velocity.rgs.roulette.persistence.RouletteRoundRepository;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.session.service.PlayerActionLock;
import com.velocity.rgs.session.service.PlayerActionLock.LockHandle;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.wallet.api.WalletAuthenticateRequest;
import com.velocity.rgs.wallet.api.WalletAuthenticateResponse;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.domain.RollbackReason;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.gateway.WalletGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application-layer orchestrator for the public roulette endpoints. Composes the math registry, the
 * {@link RouletteWheel} / {@link RouletteEvaluator} (game logic), the {@link WalletGateway} (money), the
 * shared {@link SessionStore} (identity + balance) and the {@link PlayerActionLock} (concurrency) into one
 * atomic spin. Roulette is stateless per round — no free-spins/feature FSM — so a spin is simply
 * validate → debit → draw → settle → credit → persist. All game logic is server-side; the client only
 * places bets and renders the returned result. Idempotency for client retries is handled upstream by
 * {@code IdempotencyAspect} on the controller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouletteEngineService {

    private final RouletteCatalogRegistry catalog;
    private final RouletteWheel wheel;
    private final RouletteEvaluator evaluator;
    private final WalletGateway walletGateway;
    private final SessionStore sessionStore;
    private final PlayerActionLock actionLock;
    private final RouletteRoundRepository roundRepository;
    private final ObjectMapper objectMapper;

    private static final List<GameCommand> SPIN_ONLY = List.of(GameCommand.SPIN);

    // ---------------------------------------------------------------- /init

    @Transactional
    public RouletteInitResponse init(RouletteInitRequest request, String playerId, String currency) {
        requireCurrencyMatch(request.currency(), currency);
        WalletAuthenticateResponse auth = walletGateway.authenticate(
                new WalletAuthenticateRequest(playerId), currency);
        if (!auth.eligible()) {
            throw new RgsException(ErrorCode.AUTH_FAILED,
                    "Wallet rejected player as ineligible: " + playerId);
        }

        GameSession session = sessionStore.findByPlayerId(playerId)
                .filter(s -> s.getGameId().equals(request.gameId()) && s.getCurrency().equals(currency))
                .orElseGet(() -> createSession(request.gameId(), playerId, currency, auth));

        RouletteMathDefinition math = catalog.require(session.getGameId(), session.getMathVersion()).math();
        return RouletteInitResponse.builder()
                .sessionId(session.getSessionId())
                .sessionVersion(session.getSessionVersion())
                .gameId(session.getGameId())
                .mathVersion(session.getMathVersion())
                .currency(session.getCurrency())
                .balance(auth.balance())
                .betValues(math.betConfig().values())
                .defaultBet(math.betConfig().defaultBet())
                .availableActions(SPIN_ONLY)
                .build();
    }

    // ---------------------------------------------------------------- /spin

    @Transactional
    public RouletteSpinResponse spin(RouletteSpinRequest request, String playerId) {
        LockHandle handle = actionLock.acquire(playerId);
        try {
            GameSession session = requireSession(request.sessionId(), playerId, request.gameId(),
                    request.sessionVersion());
            RouletteMathDefinition math = catalog.require(session.getGameId(), session.getMathVersion()).math();
            String currency = session.getCurrency();

            List<RouletteBet> bets = validateAndMapBets(request.bets(), math);
            BigDecimal totalBet = bets.stream()
                    .map(RouletteBet::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP);
            if (totalBet.compareTo(math.limits().maxTotalBet()) > 0) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Total stake " + totalBet + " exceeds table limit " + math.limits().maxTotalBet());
            }

            String roundId = "rou-" + UUID.randomUUID();
            RngDrawSink sink = RngDrawSink.inMemory();
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);

            // 1. Debit the total stake up front.
            String betTxId = roundId + ":bet";
            executeDebit(playerId, session, roundId, betTxId, Money.of(totalBet, currency));

            // 2. Spin the wheel and settle every bet (all game logic server-side).
            RouletteSpin outcome = wheel.spin(math, rng);
            RouletteEvaluation evaluation = evaluator.evaluate(outcome.number(), bets, math);
            BigDecimal totalWin = evaluation.totalWin();

            // 3. Credit winnings (rollback the bet debit on a credit failure).
            String winTxId = null;
            if (totalWin.signum() > 0) {
                winTxId = roundId + ":win";
                executeCredit(playerId, session, roundId, winTxId, Money.of(totalWin, currency));
            }

            // 4. Persist the round and refresh the session timestamp.
            RouletteRound round = persistRound(session, roundId, outcome, bets, evaluation,
                    totalBet, totalWin, sink, betTxId, winTxId);
            session.setUpdatedAt(Instant.now());
            GameSession saved = sessionStore.save(session);

            BigDecimal balance = walletGateway.balance(playerId).balance();

            return RouletteSpinResponse.builder()
                    .sessionId(saved.getSessionId())
                    .sessionVersion(saved.getSessionVersion())
                    .roundId(round.getRoundId())
                    .mathVersion(saved.getMathVersion())
                    .winningNumber(outcome.number())
                    .winningColor(outcome.color().name())
                    .totalBet(totalBet)
                    .totalWin(totalWin)
                    .balance(balance)
                    .winningBets(toWinningBetViews(evaluation.results()))
                    .availableActions(SPIN_ONLY)
                    .build();
        } finally {
            actionLock.release(handle);
        }
    }

    // ===================================================================== validation

    /**
     * Validates the incoming bets against the game's bet types, chip values and per-spot limit, and maps each
     * to an engine {@link RouletteBet}. The server is the single authority — a tampered or stale client
     * cannot wager an unknown bet type, an off-grid chip, an out-of-range straight number, or over the limit.
     */
    private List<RouletteBet> validateAndMapBets(List<RouletteBetRequest> requested,
                                                 RouletteMathDefinition math) {
        if (requested == null || requested.isEmpty()) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR, "At least one bet is required");
        }
        List<RouletteBet> bets = new ArrayList<>(requested.size());
        for (RouletteBetRequest r : requested) {
            RouletteBetKind kind = parseKind(r.type());
            if (math.betType(kind).isEmpty()) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Bet type " + kind + " is not offered by game " + math.gameId());
            }
            if (!math.betConfig().isValidBet(r.amount())) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Chip amount " + r.amount() + " is not an allowed stake for game " + math.gameId());
            }
            if (r.amount().compareTo(math.limits().maxBetPerSpot()) > 0) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Bet " + r.amount() + " exceeds per-spot limit " + math.limits().maxBetPerSpot());
            }
            Integer number = null;
            if (kind.requiresNumber()) {
                if (r.number() == null || r.number() < 0 || r.number() > math.highestNumber()) {
                    throw new RgsException(ErrorCode.VALIDATION_ERROR,
                            "STRAIGHT bet requires a number in [0, " + math.highestNumber() + "], found "
                                    + r.number());
                }
                number = r.number();
            }
            bets.add(new RouletteBet(kind, number, r.amount()));
        }
        return bets;
    }

    private RouletteBetKind parseKind(String type) {
        try {
            return RouletteBetKind.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR, "Unknown bet type: " + type);
        }
    }

    // ===================================================================== helpers

    private GameSession createSession(String gameId, String playerId, String currency,
                                      WalletAuthenticateResponse auth) {
        if (auth.currency() != null && !auth.currency().equals(currency)) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "Wallet currency " + auth.currency() + " does not match JWT currency " + currency);
        }
        RouletteMathDefinition math = catalog.requireByGameId(gameId).math();
        GameSession session = GameSession.builder()
                .sessionId("ses-" + UUID.randomUUID())
                .playerId(playerId)
                .gameId(gameId)
                .mathVersion(math.mathVersion())
                .currency(currency)
                .currentState(GameState.BASE_GAME)
                .currentBet(math.betConfig().defaultBet())
                .remainingFreeSpins(0)
                .accumulatedFreeSpinsWin(BigDecimal.ZERO)
                .activeFeaturePayload(null)
                .nextActionAllowed(GameCommand.SPIN.name())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return sessionStore.save(session);
    }

    private GameSession requireSession(String sessionId, String playerId, String gameId, long expectedVersion) {
        GameSession session = sessionStore.requireBySessionId(sessionId);
        if (!session.getPlayerId().equals(playerId)) {
            throw new RgsException(ErrorCode.FORBIDDEN_ACTION,
                    "Session " + sessionId + " does not belong to player " + playerId);
        }
        if (!session.getGameId().equals(gameId)) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Session " + sessionId + " is bound to gameId " + session.getGameId()
                            + ", request gameId=" + gameId);
        }
        if (session.getSessionVersion() != expectedVersion) {
            throw new RgsException(ErrorCode.SESSION_VERSION_CONFLICT,
                    "Session version mismatch: expected " + expectedVersion
                            + " actual " + session.getSessionVersion());
        }
        if (!catalog.contains(gameId)) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Session " + sessionId + " is not a roulette game: " + gameId);
        }
        return session;
    }

    private void requireCurrencyMatch(String requested, String jwtCurrency) {
        if (!requested.equalsIgnoreCase(jwtCurrency)) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "Request currency " + requested + " does not match JWT currency " + jwtCurrency);
        }
    }

    private void executeDebit(String playerId, GameSession session, String roundId, String txId, Money amount) {
        WalletDebitRequest req = new WalletDebitRequest(playerId, session.getSessionId(), roundId, txId,
                amount.amount(), session.getCurrency(), WalletTransactionType.BET);
        walletGateway.debit(req, txId, session.getCurrency());
        log.debug("roulette debit ok player={} txId={} amount={}", playerId, txId, amount.amount());
    }

    private void executeCredit(String playerId, GameSession session, String roundId, String txId, Money amount) {
        WalletCreditRequest req = new WalletCreditRequest(playerId, session.getSessionId(), roundId, txId,
                amount.amount(), session.getCurrency(), WalletTransactionType.WIN);
        try {
            walletGateway.credit(req, txId, session.getCurrency());
            log.debug("roulette credit ok player={} txId={} amount={}", playerId, txId, amount.amount());
        } catch (RuntimeException ex) {
            log.error("roulette credit failed — rolling back the bet debit player={} roundId={} cause={}",
                    playerId, roundId, ex.getMessage());
            String rollbackTxId = txId + ":rollback";
            try {
                walletGateway.rollback(new WalletRollbackRequest(playerId, roundId, rollbackTxId,
                        RollbackReason.TECHNICAL_ERROR), rollbackTxId, session.getCurrency());
            } catch (RuntimeException rollbackEx) {
                log.error("roulette rollback also failed player={} roundId={}", playerId, roundId, rollbackEx);
            }
            throw ex;
        }
    }

    private RouletteRound persistRound(GameSession session, String roundId, RouletteSpin outcome,
                                       List<RouletteBet> bets, RouletteEvaluation evaluation,
                                       BigDecimal totalBet, BigDecimal totalWin, RngDrawSink sink,
                                       String betTxId, String winTxId) {
        String currency = session.getCurrency();
        RouletteRound round = new RouletteRound();
        round.setSessionId(session.getSessionId());
        round.setPlayerId(session.getPlayerId());
        round.setRoundId(roundId);
        round.setGameId(session.getGameId());
        round.setMathVersion(session.getMathVersion());
        round.setCurrency(currency);
        round.setWinningNumber(outcome.number());
        round.setWinningColor(outcome.color().name());
        round.setTotalBet(totalBet);
        round.setTotalBetMinor(Money.of(totalBet, currency).toMinor());
        round.setTotalWin(totalWin);
        round.setTotalWinMinor(Money.of(totalWin, currency).toMinor());
        round.setBets(serializeToJson(bets.stream()
                .map(b -> Map.of("type", b.kind().name(),
                        "number", b.number() == null ? "" : b.number(),
                        "amount", b.amount()))
                .toList()));
        round.setWinningBets(serializeToJson(evaluation.results().stream()
                .map(r -> Map.of("type", r.kind().name(),
                        "number", r.number() == null ? "" : r.number(),
                        "amount", r.amount(),
                        "won", r.won(),
                        "payout", r.payout(),
                        "winAmount", r.winAmount()))
                .toList()));
        round.setRngDraws(serializeToJson(sink.drawn()));
        round.setBetTransactionId(betTxId);
        round.setWinTransactionId(winTxId);
        round.setCreatedAt(Instant.now());
        return roundRepository.save(round);
    }

    private List<RouletteSpinResponse.WinningBetView> toWinningBetViews(List<RouletteBetResult> results) {
        return results.stream()
                .map(r -> RouletteSpinResponse.WinningBetView.builder()
                        .type(r.kind().name())
                        .number(r.number())
                        .amount(r.amount())
                        .won(r.won())
                        .payout(r.payout())
                        .winAmount(r.winAmount())
                        .build())
                .toList();
    }

    private String serializeToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot serialize JSON: " + ex.getMessage(), ex);
        }
    }
}
