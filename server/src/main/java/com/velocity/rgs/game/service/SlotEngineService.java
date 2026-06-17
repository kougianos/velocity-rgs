package com.velocity.rgs.game.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.audit.pickaudit.PickAuditEvent;
import com.velocity.rgs.audit.pickaudit.PickCollectStateHasher;
import com.velocity.rgs.game.api.FeatureBuyRequest;
import com.velocity.rgs.game.api.FeatureBuyResponse;
import com.velocity.rgs.game.api.FeaturePickRequest;
import com.velocity.rgs.game.api.FeaturePickResponse;
import com.velocity.rgs.game.api.FeatureStartRequest;
import com.velocity.rgs.game.api.FeatureStartResponse;
import com.velocity.rgs.game.api.SlotInitRequest;
import com.velocity.rgs.game.api.SlotInitResponse;
import com.velocity.rgs.game.api.SpinRequest;
import com.velocity.rgs.game.api.SpinResponse;
import com.velocity.rgs.game.domain.FeaturePurchaseEvent;
import com.velocity.rgs.game.domain.GameRound;
import com.velocity.rgs.game.domain.PickCollectSnapshot;
import com.velocity.rgs.game.feature.bonusbuy.BonusBuyPolicyService;
import com.velocity.rgs.game.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.game.feature.pickcollect.PickCollectFeatureView;
import com.velocity.rgs.game.feature.pickcollect.PickCollectState;
import com.velocity.rgs.game.feature.pickcollect.PickCollectTile;
import com.velocity.rgs.game.persistence.FeaturePurchaseEventRepository;
import com.velocity.rgs.game.persistence.GameRoundRepository;
import com.velocity.rgs.game.persistence.PickCollectSnapshotRepository;
import com.velocity.rgs.math.config.BonusBuyOption;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.config.SlotMathRegistry;
import com.velocity.rgs.math.domain.PickTileType;
import com.velocity.rgs.math.domain.ReelStripSet;
import com.velocity.rgs.math.domain.SymbolType;
import com.velocity.rgs.math.engine.EvaluationResult;
import com.velocity.rgs.math.engine.GridGenerationEngine;
import com.velocity.rgs.math.engine.GridGenerationResult;
import com.velocity.rgs.math.engine.ReelEvaluator;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.session.fsm.MonetaryEffect;
import com.velocity.rgs.session.fsm.SessionCommand;
import com.velocity.rgs.session.fsm.SessionState;
import com.velocity.rgs.session.fsm.SessionStateMachine;
import com.velocity.rgs.session.fsm.TransitionContext;
import com.velocity.rgs.session.fsm.TransitionResult;
import com.velocity.rgs.session.service.PlayerActionLock;
import com.velocity.rgs.session.service.PlayerActionLock.LockHandle;
import com.velocity.rgs.session.service.SessionStore;
import com.velocity.rgs.wallet.api.WalletAuthenticateRequest;
import com.velocity.rgs.wallet.api.WalletAuthenticateResponse;
import com.velocity.rgs.wallet.api.WalletBalanceResponse;
import com.velocity.rgs.wallet.api.WalletCreditRequest;
import com.velocity.rgs.wallet.api.WalletCreditResponse;
import com.velocity.rgs.wallet.api.WalletDebitRequest;
import com.velocity.rgs.wallet.api.WalletDebitResponse;
import com.velocity.rgs.wallet.api.WalletRollbackRequest;
import com.velocity.rgs.wallet.domain.RollbackReason;
import com.velocity.rgs.wallet.domain.WalletTransactionType;
import com.velocity.rgs.wallet.gateway.WalletGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single application-layer orchestrator that fronts every Slot Game public endpoint per A.7 / M5
 * Task 5.8. Composes the FSM (state legality), {@link GridGenerationEngine} / {@link ReelEvaluator}
 * (math), {@link PickCollectEngine} (feature), {@link WalletGateway} (money), {@link SessionStore}
 * (durability + Redis cache), and {@link PlayerActionLock} (concurrency) into one atomic flow per
 * request. Idempotency for repeated client retries is handled upstream by {@code IdempotencyAspect}
 * on the controller; this service is therefore free to execute the underlying state mutation
 * directly — when a replay hits, the controller short-circuits with the cached response before this
 * service is ever entered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotEngineService {

    private static final BigDecimal DEFAULT_BET = new BigDecimal("1.00");

    private final SessionStateMachine stateMachine;
    private final GridGenerationEngine gridEngine;
    private final ReelEvaluator reelEvaluator;
    private final PickCollectEngine pickCollectEngine;
    private final BonusBuyPolicyService bonusBuyPolicyService;
    private final SlotMathRegistry mathRegistry;
    private final WalletGateway walletGateway;
    private final SessionStore sessionStore;
    private final PlayerActionLock actionLock;
    private final GameRoundRepository roundRepository;
    private final FeaturePurchaseEventRepository featurePurchaseRepository;
    private final PickCollectSnapshotRepository pickCollectRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ---------------------------------------------------------------- /init

    @Transactional
    public SlotInitResponse init(SlotInitRequest request, String playerId, String currency) {
        requireCurrencyMatch(request.currency(), currency);
        WalletAuthenticateResponse auth = walletGateway.authenticate(
                new WalletAuthenticateRequest(playerId), currency);
        if (!auth.eligible()) {
            throw new RgsException(ErrorCode.AUTH_FAILED,
                    "Wallet rejected player as ineligible: " + playerId);
        }

        GameSession session = sessionStore.findByPlayerId(playerId)
                .filter(s -> s.getGameId().equals(request.gameId())
                        && s.getCurrency().equals(currency))
                .orElseGet(() -> createSession(request.gameId(), playerId, currency, auth));

        SlotMathDefinition math = mathRegistry.require(session.getGameId(), session.getMathVersion());
        PickCollectFeatureView view = pickCollectViewIfActive(session);
        List<GameCommand> actions = availableActions(session, math);

        return SlotInitResponse.builder()
                .sessionId(session.getSessionId())
                .sessionVersion(session.getSessionVersion())
                .gameId(session.getGameId())
                .mathVersion(session.getMathVersion())
                .currency(session.getCurrency())
                .balance(auth.balance())
                .currentState(session.getCurrentState())
                .remainingFreeSpins(session.getRemainingFreeSpins())
                .accumulatedFreeSpinsWin(session.getAccumulatedFreeSpinsWin())
                .currentBet(session.getCurrentBet())
                .availableActions(actions)
                .featureFlags(Map.of(
                        "powerBetEnabled", true,
                        "bonusBuyEnabled", !math.bonusBuyOptions().isEmpty()))
                .activeFeatureView(view)
                .build();
    }

    // ---------------------------------------------------------------- /spin

    @Transactional
    public SpinResponse spin(SpinRequest request, String playerId) {
        LockHandle handle = actionLock.acquire(playerId);
        try {
            GameSession session = requireSession(request.sessionId(), playerId, request.gameId(),
                    request.sessionVersion());
            SlotMathDefinition math = mathRegistry.require(session.getGameId(), session.getMathVersion());

            SessionState currentState = rehydrate(session);
            BigDecimal effectiveBet = effectiveBetForSpin(currentState, request.betSize(), math);

            TransitionContext ctx = new TransitionContext(math, session.getCurrency());
            TransitionResult transition = stateMachine.transition(currentState,
                    new SessionCommand.SpinCommand(effectiveBet, request.powerBetActive()), ctx);

            String roundId = "rnd-" + UUID.randomUUID();
            RngDrawSink sink = RngDrawSink.inMemory();
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);
            ReelStripSet stripSet = pickReelSet(currentState, request.powerBetActive());

            GridGenerationResult grid = gridEngine.generate(math, stripSet, rng);
            EvaluationResult evaluation = reelEvaluator.evaluate(grid.matrix(), effectiveBet, math);

            BigDecimal betDebited = BigDecimal.ZERO;
            String betTxId = null;
            if (transition.monetaryEffect() instanceof MonetaryEffect.Debit debit) {
                betTxId = roundId + ":bet";
                executeDebit(playerId, session, roundId, betTxId, debit);
                betDebited = debit.amount().amount();
            }

            BigDecimal totalWin = evaluation.totalWin();
            String winTxId = null;
            List<String> reasonCodes = new ArrayList<>(evaluation.reasonCodes());

            SpinPostProcessing post = postProcessSpin(transition.newState(), session, math, evaluation,
                    grid, effectiveBet, request.powerBetActive());
            reasonCodes.addAll(post.reasonCodes());
            SessionState newState = post.newState();

            if (post.creditAmount() != null && post.creditAmount().signum() > 0) {
                winTxId = roundId + ":win";
                executeCredit(playerId, session, roundId, winTxId,
                        Money.of(post.creditAmount(), session.getCurrency()),
                        post.creditType());
                totalWin = post.creditAmount();
            }

            applyStateToSession(session, newState, effectiveBet, post.bonusBuyExecuted());
            session.setUpdatedAt(Instant.now());
            GameSession saved = sessionStore.save(session);

            GameRound round = persistRound(session, roundId, effectiveBet,
                    Money.of(totalWin, session.getCurrency()),
                    grid, evaluation, sink, reasonCodes, request.powerBetActive(),
                    betTxId, winTxId, newState.gameState());

            List<GameCommand> actions = transitionActions(newState, math, transition.availableActions());

            return SpinResponse.builder()
                    .sessionId(saved.getSessionId())
                    .sessionVersion(saved.getSessionVersion())
                    .roundId(round.getRoundId())
                    .mathVersion(saved.getMathVersion())
                    .betDebited(betDebited)
                    .totalWin(totalWin)
                    .matrix(grid.matrix())
                    .stopPositions(grid.stopPositions())
                    .winLines(evaluation.winLines())
                    .featuresTriggered(SpinResponse.FeaturesTriggered.builder()
                            .freeSpinsAwarded(post.freeSpinsAwarded())
                            .isPowerBetActive(request.powerBetActive())
                            .pickCollectTriggered(post.pickCollectTriggered())
                            .bonusBuyExecuted(post.bonusBuyExecuted())
                            .reasonCodes(reasonCodes)
                            .build())
                    .sessionState(SpinResponse.SessionStateView.builder()
                            .currentState(saved.getCurrentState())
                            .remainingFreeSpins(saved.getRemainingFreeSpins())
                            .accumulatedFreeSpinsWin(saved.getAccumulatedFreeSpinsWin())
                            .build())
                    .availableActions(actions)
                    .build();
        } finally {
            actionLock.release(handle);
        }
    }

    // ---------------------------------------------------------------- /feature/start

    @Transactional
    public FeatureStartResponse startFeature(FeatureStartRequest request, String playerId) {
        LockHandle handle = actionLock.acquire(playerId);
        try {
            GameSession session = requireSession(request.sessionId(), playerId, request.gameId(),
                    request.sessionVersion());
            SlotMathDefinition math = mathRegistry.require(session.getGameId(), session.getMathVersion());
            SessionState currentState = rehydrate(session);

            SessionCommand command = switch (request.featureType()) {
                case "FREE_SPINS" -> new SessionCommand.StartFreeSpinsCommand();
                case "PICK_COLLECT" -> new SessionCommand.StartPickCollectCommand();
                default -> throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Unknown featureType: " + request.featureType());
            };

            TransitionContext ctx = new TransitionContext(math, session.getCurrency());
            TransitionResult transition = stateMachine.transition(currentState, command, ctx);
            SessionState newState = transition.newState();

            if (command instanceof SessionCommand.StartPickCollectCommand) {
                BigDecimal triggerBet = session.getCurrentBet();
                PickCollectState pcState = pickCollectEngine.startFeature(math.pickCollect(),
                        triggerBet, new SecureRandomNumberGenerator(RngDrawSink.inMemory()),
                        initialPicksFor(math, newState));
                persistPickCollectSnapshot(session, pcState, "ACTIVE");
                newState = new SessionState.PickCollectLoop(serializeFeaturePayload(pcState));
            }

            applyStateToSession(session, newState, session.getCurrentBet(), false);
            session.setUpdatedAt(Instant.now());
            GameSession saved = sessionStore.save(session);

            PickCollectFeatureView view = pickCollectViewIfActive(saved);
            return FeatureStartResponse.builder()
                    .sessionId(saved.getSessionId())
                    .sessionVersion(saved.getSessionVersion())
                    .currentState(saved.getCurrentState())
                    .remainingFreeSpins(saved.getRemainingFreeSpins())
                    .accumulatedFreeSpinsWin(saved.getAccumulatedFreeSpinsWin())
                    .activeFeatureView(view)
                    .availableActions(transition.availableActions())
                    .build();
        } finally {
            actionLock.release(handle);
        }
    }

    // ---------------------------------------------------------------- /feature/buy

    @Transactional
    public FeatureBuyResponse buyFeature(FeatureBuyRequest request, String playerId, String jurisdiction) {
        LockHandle handle = actionLock.acquire(playerId);
        try {
            GameSession session = requireSession(request.sessionId(), playerId, request.gameId(),
                    request.sessionVersion());
            SlotMathDefinition math = mathRegistry.require(session.getGameId(), session.getMathVersion());

            WalletBalanceResponse balance = walletGateway.balance(playerId);
            BonusBuyOption option = bonusBuyPolicyService.requireOption(math, request.buyType(),
                    session, balance.balance(), request.betSize(), jurisdiction);
            BigDecimal cost = request.betSize().multiply(option.costMultiplier());

            SessionState currentState = rehydrate(session);
            TransitionContext ctx = new TransitionContext(math, session.getCurrency());
            TransitionResult transition = stateMachine.transition(currentState,
                    new SessionCommand.BuyFeatureCommand(request.buyType(), request.betSize()), ctx);

            String roundId = "buy-" + UUID.randomUUID();
            String buyTxId = roundId + ":bonus-buy";

            executeDebit(playerId, session, roundId, buyTxId,
                    (MonetaryEffect.Debit) transition.monetaryEffect());

            SessionState newState = transition.newState();
            PickCollectState pcState = null;
            if (option.targetState() == GameState.PICK_COLLECT_AWAITING) {
                pcState = pickCollectEngine.startFeature(math.pickCollect(),
                        request.betSize(),
                        new SecureRandomNumberGenerator(RngDrawSink.inMemory()),
                        Integer.parseInt(option.initialFeaturePayload()
                                .getOrDefault("maxPicks", math.pickCollect().completion().value())
                                .toString()));
                persistPickCollectSnapshot(session, pcState, "ACTIVE");
            }

            applyStateToSession(session, newState, request.betSize(), true);
            session.setUpdatedAt(Instant.now());
            GameSession saved = sessionStore.save(session);

            FeaturePurchaseEvent event = new FeaturePurchaseEvent();
            event.setPlayerId(playerId);
            event.setSessionId(saved.getSessionId());
            event.setBuyType(request.buyType());
            event.setCost(cost);
            event.setCostMinor(Money.of(cost, saved.getCurrency()).toMinor());
            event.setCurrency(saved.getCurrency());
            event.setIdempotencyKey(buyTxId);
            event.setResultingState(saved.getCurrentState());
            event.setTransactionId(buyTxId);
            event.setBetSize(request.betSize());
            event.setCreatedAt(Instant.now());
            featurePurchaseRepository.save(event);

            return FeatureBuyResponse.builder()
                    .sessionId(saved.getSessionId())
                    .sessionVersion(saved.getSessionVersion())
                    .buyType(request.buyType())
                    .cost(cost)
                    .currency(saved.getCurrency())
                    .enteredState(saved.getCurrentState())
                    .featureInitPayload(option.initialFeaturePayload())
                    .activeFeatureView(pcState != null ? PickCollectFeatureView.of(pcState) : null)
                    .availableActions(transition.availableActions())
                    .build();
        } finally {
            actionLock.release(handle);
        }
    }

    // ---------------------------------------------------------------- /feature/pick

    @Transactional
    public FeaturePickResponse pickFeature(FeaturePickRequest request, String playerId) {
        LockHandle handle = actionLock.acquire(playerId);
        try {
            GameSession session = requireSession(request.sessionId(), playerId, request.gameId(),
                    request.sessionVersion());
            SlotMathDefinition math = mathRegistry.require(session.getGameId(), session.getMathVersion());
            if (session.getCurrentState() != GameState.PICK_COLLECT_LOOP) {
                throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                        "Pick command only allowed in PICK_COLLECT_LOOP");
            }

            PickCollectState pcState = loadPickCollectState(session, math, session.getCurrentBet());

            String beforeHash = PickCollectStateHasher.hash(pcState);
            BigDecimal collectedBefore = pcState.currentCollected();
            BigDecimal totalBefore = pcState.totalFeatureWin();
            int picksBefore = pcState.remainingPicks();

            PickCollectEngine.PickResolution resolution = pickCollectEngine.applyPick(
                    pcState, request.position(), math.pickCollect());

            BigDecimal totalWin = BigDecimal.ZERO;
            List<String> reasons = new ArrayList<>(resolution.reasonCodes());
            SessionState newState = new SessionState.PickCollectLoop(serializeFeaturePayload(pcState));
            boolean completed = pcState.status() == PickCollectState.Status.COMPLETED;
            if (completed) {
                PickCollectEngine.FinalizationResult finalize = pickCollectEngine.finalizeFeature(
                        pcState, math.pickCollect(), session.getCurrency());
                String creditTxId = "pick-" + session.getSessionId() + "-" + Instant.now().toEpochMilli();
                if (finalize.finalWin().amount().signum() > 0) {
                    executeCredit(playerId, session, creditTxId, creditTxId,
                            finalize.finalWin(), WalletTransactionType.FEATURE_WIN);
                }
                totalWin = finalize.finalWin().amount();
                reasons.addAll(finalize.reasonCodes());
                reasons.add("PICK_FEATURE_SETTLED");
                newState = new SessionState.BaseGame();
                persistPickCollectSnapshot(session, pcState, "COMPLETED");
            } else {
                persistPickCollectSnapshot(session, pcState, "ACTIVE");
            }

            applyStateToSession(session, newState, session.getCurrentBet(), false);
            session.setUpdatedAt(Instant.now());
            GameSession saved = sessionStore.save(session);

            String afterHash = PickCollectStateHasher.hash(pcState);
            eventPublisher.publishEvent(new PickAuditEvent(
                    playerId, saved.getSessionId(), request.position(),
                    resolution.resolvedTileType(), resolution.resolvedValue(),
                    beforeHash, afterHash,
                    collectedBefore, pcState.currentCollected(),
                    totalBefore, pcState.totalFeatureWin(),
                    picksBefore, pcState.remainingPicks(),
                    completed, Instant.now()
            ));

            return FeaturePickResponse.builder()
                    .sessionId(saved.getSessionId())
                    .sessionVersion(saved.getSessionVersion())
                    .position(request.position())
                    .resolvedTileType(resolution.resolvedTileType())
                    .resolvedValue(resolution.resolvedValue())
                    .currentCollected(pcState.currentCollected())
                    .remainingPicks(pcState.remainingPicks())
                    .featureCompleted(completed)
                    .featureTotalWin(totalWin)
                    .currentState(saved.getCurrentState())
                    .activeFeatureView(completed ? null : PickCollectFeatureView.of(pcState))
                    .reasonCodes(reasons)
                    .availableActions(availableActions(saved, math))
                    .build();
        } finally {
            actionLock.release(handle);
        }
    }

    // ===================================================================== helpers

    private GameSession createSession(String gameId, String playerId, String currency,
                                      WalletAuthenticateResponse auth) {
        if (auth.currency() != null && !auth.currency().equals(currency)) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "Wallet currency " + auth.currency() + " does not match JWT currency " + currency);
        }
        SlotMathDefinition math = mathRegistry.require(gameId, defaultMathVersion(gameId));
        GameSession session = GameSession.builder()
                .sessionId("ses-" + UUID.randomUUID())
                .playerId(playerId)
                .gameId(gameId)
                .mathVersion(math.mathVersion())
                .currency(currency)
                .currentState(GameState.BASE_GAME)
                .currentBet(DEFAULT_BET)
                .remainingFreeSpins(0)
                .accumulatedFreeSpinsWin(BigDecimal.ZERO)
                .activeFeaturePayload(null)
                .nextActionAllowed(GameCommand.SPIN.name())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return sessionStore.save(session);
    }

    private String defaultMathVersion(String gameId) {
        return mathRegistry.all().stream()
                .filter(m -> m.gameId().equals(gameId))
                .map(SlotMathDefinition::mathVersion)
                .findFirst()
                .orElseThrow(() -> new RgsException(ErrorCode.VALIDATION_ERROR,
                        "No math definition registered for gameId=" + gameId));
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
        return session;
    }

    private void requireCurrencyMatch(String requested, String jwtCurrency) {
        if (!requested.equalsIgnoreCase(jwtCurrency)) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "Request currency " + requested + " does not match JWT currency " + jwtCurrency);
        }
    }

    private SessionState rehydrate(GameSession session) {
        return switch (session.getCurrentState()) {
            case BASE_GAME -> new SessionState.BaseGame();
            case FREE_SPINS_AWAITING -> new SessionState.FreeSpinsAwaiting(
                    Math.max(1, session.getRemainingFreeSpins()), session.getCurrentBet());
            case FREE_SPINS_LOOP -> new SessionState.FreeSpinsLoop(
                    session.getRemainingFreeSpins(), session.getAccumulatedFreeSpinsWin(),
                    session.getCurrentBet());
            case PICK_COLLECT_AWAITING -> new SessionState.PickCollectAwaiting(
                    deserializeMap(session.getActiveFeaturePayload()));
            case PICK_COLLECT_LOOP -> new SessionState.PickCollectLoop(
                    deserializeMap(session.getActiveFeaturePayload()));
        };
    }

    private BigDecimal effectiveBetForSpin(SessionState state, BigDecimal requestedBet,
                                           SlotMathDefinition math) {
        if (state instanceof SessionState.FreeSpinsLoop loop) {
            BigDecimal locked = math.freeSpins().betLockedToTriggerBet() ? loop.triggerBet() : requestedBet;
            return locked;
        }
        return requestedBet;
    }

    private ReelStripSet pickReelSet(SessionState state, boolean powerBetActive) {
        if (state instanceof SessionState.FreeSpinsLoop) {
            return ReelStripSet.FREE_SPINS;
        }
        if (state instanceof SessionState.BaseGame && powerBetActive) {
            return ReelStripSet.POWER_BET;
        }
        return ReelStripSet.BASE;
    }

    private SpinPostProcessing postProcessSpin(SessionState afterFsm, GameSession session,
                                               SlotMathDefinition math, EvaluationResult evaluation,
                                               GridGenerationResult grid, BigDecimal bet,
                                               boolean powerBetActive) {
        int scatterCount = countScatters(grid.matrix(), math);
        List<String> reasonCodes = new ArrayList<>();
        BigDecimal creditAmount = BigDecimal.ZERO;
        WalletTransactionType creditType = WalletTransactionType.WIN;
        SessionState newState = afterFsm;
        int freeSpinsAwarded = 0;
        boolean pickCollectTriggered = false;

        if (afterFsm instanceof SessionState.BaseGame) {
            if (evaluation.totalWin().signum() > 0) {
                creditAmount = evaluation.totalWin();
                creditType = WalletTransactionType.WIN;
            }
            if (scatterCount >= math.scatterTriggers().minCount()) {
                freeSpinsAwarded = math.scatterTriggers().freeSpinsAwarded();
                newState = new SessionState.FreeSpinsAwaiting(freeSpinsAwarded, bet);
                reasonCodes.add("TRIGGERED_BY_SCATTER");
            }
        } else if (afterFsm instanceof SessionState.FreeSpinsLoop loop) {
            BigDecimal acc = loop.accumulatedWin().add(evaluation.totalWin());
            int remaining = loop.remainingFreeSpins() - 1;
            if (scatterCount >= math.scatterTriggers().minCount()
                    && math.scatterTriggers().retriggerAwards() > 0) {
                remaining += math.scatterTriggers().retriggerAwards();
                reasonCodes.add("RETRIGGERED_FREE_SPINS");
            }
            if (remaining <= 0) {
                creditAmount = acc;
                creditType = WalletTransactionType.FEATURE_WIN;
                reasonCodes.add("FREE_SPINS_SETTLED");
                newState = new SessionState.BaseGame();
            } else {
                newState = new SessionState.FreeSpinsLoop(remaining, acc, loop.triggerBet());
            }
        }
        return new SpinPostProcessing(newState, creditAmount, creditType, reasonCodes,
                freeSpinsAwarded, pickCollectTriggered, false);
    }

    private int countScatters(int[][] matrix, SlotMathDefinition math) {
        int scatterId = math.symbols().stream()
                .filter(s -> s.type() == SymbolType.SCATTER)
                .mapToInt(s -> s.id())
                .findFirst()
                .orElse(-1);
        if (scatterId < 0) {
            return 0;
        }
        int count = 0;
        for (int[] col : matrix) {
            for (int sym : col) {
                if (sym == scatterId) count++;
            }
        }
        return count;
    }

    private void executeDebit(String playerId, GameSession session, String roundId, String txId,
                              MonetaryEffect.Debit debit) {
        WalletDebitRequest debitRequest = new WalletDebitRequest(playerId, session.getSessionId(),
                roundId, txId, debit.amount().amount(), session.getCurrency(), debit.type());
        try {
            WalletDebitResponse resp = walletGateway.debit(debitRequest, txId, session.getCurrency());
            log.debug("debit ok player={} txId={} balanceAfter={}", playerId, txId, resp.balanceAfter());
        } catch (RuntimeException ex) {
            log.warn("debit failed player={} txId={} cause={}", playerId, txId, ex.getMessage());
            throw ex;
        }
    }

    private void executeCredit(String playerId, GameSession session, String roundId, String txId,
                               Money amount, WalletTransactionType type) {
        WalletCreditRequest creditRequest = new WalletCreditRequest(playerId, session.getSessionId(),
                roundId, txId, amount.amount(), session.getCurrency(), type);
        try {
            WalletCreditResponse resp = walletGateway.credit(creditRequest, txId, session.getCurrency());
            log.debug("credit ok player={} txId={} balanceAfter={}", playerId, txId, resp.balanceAfter());
        } catch (RuntimeException ex) {
            log.error("credit failed — issuing rollback of original bet player={} txId={} cause={}",
                    playerId, txId, ex.getMessage());
            String rollbackTxId = txId + ":rollback";
            try {
                walletGateway.rollback(new WalletRollbackRequest(playerId, roundId, rollbackTxId,
                        RollbackReason.TECHNICAL_ERROR), rollbackTxId, session.getCurrency());
            } catch (RuntimeException rollbackEx) {
                log.error("rollback also failed player={} roundId={}", playerId, roundId, rollbackEx);
            }
            throw ex;
        }
    }

    private void applyStateToSession(GameSession session, SessionState state, BigDecimal currentBet,
                                     boolean bonusBuyExecuted) {
        session.setCurrentState(state.gameState());
        session.setCurrentBet(currentBet);
        switch (state) {
            case SessionState.BaseGame ignored -> {
                session.setRemainingFreeSpins(0);
                session.setAccumulatedFreeSpinsWin(BigDecimal.ZERO);
                session.setActiveFeaturePayload(null);
                session.setNextActionAllowed(GameCommand.SPIN.name());
            }
            case SessionState.FreeSpinsAwaiting awaiting -> {
                session.setRemainingFreeSpins(awaiting.remainingFreeSpins());
                session.setAccumulatedFreeSpinsWin(BigDecimal.ZERO);
                session.setActiveFeaturePayload(null);
                session.setNextActionAllowed(GameCommand.START_FREE_SPINS.name());
            }
            case SessionState.FreeSpinsLoop loop -> {
                session.setRemainingFreeSpins(loop.remainingFreeSpins());
                session.setAccumulatedFreeSpinsWin(loop.accumulatedWin());
                session.setActiveFeaturePayload(null);
                session.setNextActionAllowed(GameCommand.SPIN.name());
            }
            case SessionState.PickCollectAwaiting awaiting -> {
                session.setRemainingFreeSpins(0);
                session.setAccumulatedFreeSpinsWin(BigDecimal.ZERO);
                session.setActiveFeaturePayload(serializeToJson(awaiting.initialPayload()));
                session.setNextActionAllowed(GameCommand.START_PICK_COLLECT.name());
            }
            case SessionState.PickCollectLoop loop -> {
                session.setRemainingFreeSpins(0);
                session.setAccumulatedFreeSpinsWin(BigDecimal.ZERO);
                session.setActiveFeaturePayload(serializeToJson(loop.featurePayload()));
                session.setNextActionAllowed(GameCommand.PICK.name());
            }
        }
        if (bonusBuyExecuted) {
            session.setCurrentBet(currentBet);
        }
    }

    private List<GameCommand> availableActions(GameSession session, SlotMathDefinition math) {
        return switch (session.getCurrentState()) {
            case BASE_GAME -> math.bonusBuyOptions().isEmpty()
                    ? List.of(GameCommand.SPIN)
                    : List.of(GameCommand.SPIN, GameCommand.BUY_FEATURE);
            case FREE_SPINS_AWAITING -> List.of(GameCommand.START_FREE_SPINS);
            case FREE_SPINS_LOOP -> List.of(GameCommand.SPIN);
            case PICK_COLLECT_AWAITING -> List.of(GameCommand.START_PICK_COLLECT);
            case PICK_COLLECT_LOOP -> List.of(GameCommand.PICK);
        };
    }

    private List<GameCommand> transitionActions(SessionState newState, SlotMathDefinition math,
                                                List<GameCommand> fsmActions) {
        return switch (newState) {
            case SessionState.BaseGame ignored -> math.bonusBuyOptions().isEmpty()
                    ? List.of(GameCommand.SPIN)
                    : List.of(GameCommand.SPIN, GameCommand.BUY_FEATURE);
            case SessionState.FreeSpinsAwaiting ignored -> List.of(GameCommand.START_FREE_SPINS);
            case SessionState.FreeSpinsLoop ignored -> List.of(GameCommand.SPIN);
            case SessionState.PickCollectAwaiting ignored -> List.of(GameCommand.START_PICK_COLLECT);
            case SessionState.PickCollectLoop ignored -> List.of(GameCommand.PICK);
        };
    }

    private GameRound persistRound(GameSession session, String roundId, BigDecimal betAmount,
                                   Money totalWin, GridGenerationResult grid, EvaluationResult evaluation,
                                   RngDrawSink sink, List<String> reasonCodes, boolean powerBetActive,
                                   String betTxId, String winTxId, GameState afterState) {
        GameRound round = new GameRound();
        round.setSessionId(session.getSessionId());
        round.setPlayerId(session.getPlayerId());
        round.setRoundId(roundId);
        round.setGameId(session.getGameId());
        round.setMathVersion(session.getMathVersion());
        round.setStateContext(afterState);
        round.setBetAmount(betAmount);
        round.setBetAmountMinor(Money.of(betAmount, session.getCurrency()).toMinor());
        round.setTotalWin(totalWin.amount());
        round.setTotalWinMinor(totalWin.toMinor());
        round.setCurrency(session.getCurrency());
        round.setMatrix(serializeToJson(grid.matrix()));
        round.setStopPositions(serializeToJson(grid.stopPositions()));
        round.setRngDraws(serializeToJson(sink.drawn()));
        round.setWinLines(serializeToJson(evaluation.winLines()));
        round.setReasonCodes(serializeToJson(reasonCodes));
        round.setPowerBetActive(powerBetActive);
        round.setBetTransactionId(betTxId);
        round.setWinTransactionId(winTxId);
        round.setCreatedAt(Instant.now());
        return roundRepository.save(round);
    }

    private void persistPickCollectSnapshot(GameSession session, PickCollectState state, String status) {
        PickCollectSnapshot snapshot = pickCollectRepository
                .findFirstBySessionIdOrderByCreatedAtDesc(session.getSessionId())
                .orElseGet(PickCollectSnapshot::new);
        if (snapshot.getId() == null) {
            snapshot.setSessionId(session.getSessionId());
            snapshot.setRoundId("pick-" + session.getSessionId());
            snapshot.setBoardSeed(UUID.randomUUID().toString());
            snapshot.setCreatedAt(Instant.now());
        }
        snapshot.setBoard(serializeBoard(state.tiles()));
        snapshot.setOpenedPositions(serializeToJson(state.openedPositions()));
        snapshot.setFinalWin(state.totalFeatureWin().add(state.currentCollected()));
        BigDecimal scaledFinal = snapshot.getFinalWin().multiply(state.betSize())
                .setScale(Money.minorUnitScale(session.getCurrency()), RoundingMode.HALF_UP);
        snapshot.setFinalWinMinor(Money.of(scaledFinal, session.getCurrency()).toMinor());
        snapshot.setStatus(status);
        snapshot.setUpdatedAt(Instant.now());
        pickCollectRepository.save(snapshot);
    }

    private PickCollectFeatureView pickCollectViewIfActive(GameSession session) {
        if (session.getCurrentState() != GameState.PICK_COLLECT_LOOP
                && session.getCurrentState() != GameState.PICK_COLLECT_AWAITING) {
            return null;
        }
        SlotMathDefinition math = mathRegistry.require(session.getGameId(), session.getMathVersion());
        PickCollectState state = loadPickCollectState(session, math, session.getCurrentBet());
        return PickCollectFeatureView.of(state);
    }

    private PickCollectState loadPickCollectState(GameSession session, SlotMathDefinition math,
                                                  BigDecimal triggerBet) {
        String payloadJson = session.getActiveFeaturePayload();
        if (payloadJson == null) {
            throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Active feature payload missing for session " + session.getSessionId());
        }
        try {
            FeaturePayloadEnvelope envelope = objectMapper.readValue(payloadJson,
                    FeaturePayloadEnvelope.class);
            List<PickCollectTile> tiles = envelope.tiles().stream()
                    .map(t -> new PickCollectTile(t.type(), t.value()))
                    .toList();
            PickCollectState state = new PickCollectState(tiles, triggerBet, math.pickCollect().completion(),
                    envelope.remainingPicks());
            for (Integer position : envelope.openedPositions()) {
                state.open(position);
            }
            for (var reveal : envelope.revealedPicks()) {
                state.recordReveal(new PickCollectState.RevealedPick(reveal.position(),
                        reveal.type(), reveal.value()));
            }
            state.setCurrentCollected(envelope.currentCollected());
            state.setTotalFeatureWin(envelope.totalFeatureWin());
            if (envelope.status() == PickCollectState.Status.COMPLETED) {
                state.markCompleted();
            }
            return state;
        } catch (RuntimeException ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize active feature payload: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize active feature payload", ex);
        }
    }

    private Map<String, Object> serializeFeaturePayload(PickCollectState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tiles", state.tiles().stream()
                .map(t -> Map.<String, Object>of("type", t.type().name(), "value", t.value()))
                .toList());
        payload.put("openedPositions", List.copyOf(state.openedPositions()));
        payload.put("revealedPicks", state.revealedPicks().stream()
                .map(r -> Map.<String, Object>of(
                        "position", r.position(),
                        "type", r.type().name(),
                        "value", r.value()))
                .toList());
        payload.put("currentCollected", state.currentCollected());
        payload.put("totalFeatureWin", state.totalFeatureWin());
        payload.put("remainingPicks", state.remainingPicks());
        payload.put("status", state.status().name());
        return payload;
    }

    private int initialPicksFor(SlotMathDefinition math, SessionState newState) {
        if (newState instanceof SessionState.PickCollectLoop loop) {
            Object maxPicks = loop.featurePayload().get("maxPicks");
            if (maxPicks instanceof Number n) return n.intValue();
        }
        return math.pickCollect().completion().value();
    }

    private Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize feature payload: " + ex.getMessage(), ex);
        }
    }

    private String serializeToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot serialize JSON: " + ex.getMessage(), ex);
        }
    }

    private String serializeBoard(List<PickCollectTile> tiles) {
        return serializeToJson(tiles.stream()
                .map(t -> Map.of("type", t.type().name(), "value", t.value()))
                .toList());
    }

    /** Internal post-spin computation results carried back to the {@code spin} caller. */
    private record SpinPostProcessing(
            SessionState newState,
            BigDecimal creditAmount,
            WalletTransactionType creditType,
            List<String> reasonCodes,
            int freeSpinsAwarded,
            boolean pickCollectTriggered,
            boolean bonusBuyExecuted
    ) {
    }

    /** JSON envelope used to round-trip {@link PickCollectState} via {@code activeFeaturePayload}. */
    private record FeaturePayloadEnvelope(
            List<TileDto> tiles,
            List<Integer> openedPositions,
            List<RevealDto> revealedPicks,
            BigDecimal currentCollected,
            BigDecimal totalFeatureWin,
            int remainingPicks,
            PickCollectState.Status status
    ) {
        private record TileDto(PickTileType type, BigDecimal value) {}
        private record RevealDto(int position, PickTileType type, BigDecimal value) {}
    }
}
