package com.velocity.rgs.slot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.audit.pickaudit.PickAuditEvent;
import com.velocity.rgs.audit.pickaudit.PickCollectStateHasher;
import com.velocity.rgs.slot.api.FeatureBuyRequest;
import com.velocity.rgs.slot.api.FeatureBuyResponse;
import com.velocity.rgs.slot.api.FeaturePickRequest;
import com.velocity.rgs.slot.api.FeaturePickResponse;
import com.velocity.rgs.slot.api.FeatureStartRequest;
import com.velocity.rgs.slot.api.FeatureStartResponse;
import com.velocity.rgs.slot.api.SlotInitRequest;
import com.velocity.rgs.slot.api.SlotInitResponse;
import com.velocity.rgs.slot.api.SpinRequest;
import com.velocity.rgs.slot.api.SpinResponse;
import com.velocity.rgs.slot.domain.FeaturePurchaseEvent;
import com.velocity.rgs.slot.domain.GameRound;
import com.velocity.rgs.slot.domain.PickCollectSnapshot;
import com.velocity.rgs.slot.domain.RoundKind;
import com.velocity.rgs.slot.feature.bonusbuy.BonusBuyPolicyService;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectEngine;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectFeatureView;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectState;
import com.velocity.rgs.slot.feature.pickcollect.PickCollectTile;
import com.velocity.rgs.slot.feature.respin.RespinEngine;
import com.velocity.rgs.slot.feature.respin.RespinFeatureView;
import com.velocity.rgs.slot.feature.respin.RespinPayloadCodec;
import com.velocity.rgs.slot.feature.respin.RespinState;
import com.velocity.rgs.slot.persistence.FeaturePurchaseEventRepository;
import com.velocity.rgs.slot.persistence.GameRoundRepository;
import com.velocity.rgs.slot.persistence.PickCollectSnapshotRepository;
import com.velocity.rgs.slot.persistence.RoundGridCodec;
import com.velocity.rgs.slot.math.config.BonusBuyOption;
import com.velocity.rgs.slot.math.config.SlotMathDefinition;
import com.velocity.rgs.slot.math.config.SlotMathRegistry;
import com.velocity.rgs.slot.math.domain.PickTileType;
import com.velocity.rgs.slot.math.domain.ReelStripSet;
import com.velocity.rgs.slot.math.domain.SymbolType;
import com.velocity.rgs.slot.math.engine.EvaluationResult;
import com.velocity.rgs.slot.math.engine.GridGenerationEngine;
import com.velocity.rgs.slot.math.engine.GridGenerationResult;
import com.velocity.rgs.slot.math.engine.ReelEvaluator;
import com.velocity.rgs.slot.math.engine.WildFeatureEngine;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
import com.velocity.rgs.session.domain.GameCommand;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.domain.GameState;
import com.velocity.rgs.slot.fsm.MonetaryEffect;
import com.velocity.rgs.slot.fsm.SessionCommand;
import com.velocity.rgs.slot.fsm.SessionState;
import com.velocity.rgs.slot.fsm.SessionStateMachine;
import com.velocity.rgs.slot.fsm.TransitionContext;
import com.velocity.rgs.slot.fsm.TransitionResult;
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
 * directly - when a replay hits, the controller short-circuits with the cached response before this
 * service is ever entered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotEngineService {

    private final SessionStateMachine stateMachine;
    private final GridGenerationEngine gridEngine;
    private final ReelEvaluator reelEvaluator;
    private final WildFeatureEngine wildFeatureEngine;
    private final PickCollectEngine pickCollectEngine;
    private final RespinEngine respinEngine;
    private final RespinPayloadCodec respinPayloadCodec;
    private final BonusBuyPolicyService bonusBuyPolicyService;
    private final SlotMathRegistry mathRegistry;
    private final WalletGateway walletGateway;
    private final SessionStore sessionStore;
    private final PlayerActionLock actionLock;
    private final GameRoundRepository roundRepository;
    private final FeaturePurchaseEventRepository featurePurchaseRepository;
    private final PickCollectSnapshotRepository pickCollectRepository;
    private final RoundGridCodec gridCodec;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** Key under which a bought free-spins round stashes its win multiplier in {@code active_feature_payload}. */
    private static final String FREE_SPINS_WIN_MULTIPLIER_KEY = "buyFsWinMultiplier";

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
                .featureFlags(Map.<String, Object>of(
                        "powerBetEnabled", true,
                        "powerBetMultiplier", math.powerBet().betMultiplier(),
                        "bonusBuyEnabled", !math.bonusBuyOptions().isEmpty(),
                        "cascadesEnabled", math.cascades().enabled(),
                        "respinsEnabled", math.respins().enabled()))
                .activeFeatureView(view)
                .respinView(respinViewIfActive(session, math))
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
            // A Hold & Spin respin re-draws only the unlocked cells and pays nothing per iteration, so
            // it shares almost none of the base-spin flow below (no stake choice, no debit, no line
            // evaluation). Branching here keeps both paths honest rather than threading conditionals
            // through one.
            if (currentState instanceof SessionState.RespinLoop respinLoop) {
                return respinSpin(request, playerId, session, math, respinLoop);
            }
            // The player only chooses a stake in the base game; free spins lock to the trigger bet and
            // bonus-buy flows have their own cost path. When the player does pick the stake, it must be one
            // of the game's configured bet values - the server is the authority, so a tampered or stale
            // client cannot wager an off-grid amount. Power Bet multiplies this validated base stake.
            if (currentState instanceof SessionState.BaseGame
                    && !math.betConfig().isValidBet(request.betSize())) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Bet " + request.betSize() + " is not an allowed stake for game "
                                + session.getGameId());
            }
            BigDecimal effectiveBet = effectiveBetForSpin(currentState, request.betSize(),
                    request.powerBetActive(), math, session.getCurrency());

            TransitionContext ctx = new TransitionContext(math, session.getCurrency());
            TransitionResult transition = stateMachine.transition(currentState,
                    new SessionCommand.SpinCommand(effectiveBet, request.powerBetActive()), ctx);

            String roundId = "rnd-" + UUID.randomUUID();
            RngDrawSink sink = RngDrawSink.inMemory();
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);
            ReelStripSet stripSet = pickReelSet(currentState, request.powerBetActive());

            GridGenerationResult drawn = gridEngine.generate(math, stripSet, rng);
            // Expanding / sticky / walking wilds reshape the board before anything evaluates it, and it
            // is the reshaped board that gets persisted below - so replay has to re-apply this transform
            // rather than compare against the drawn grid. ReplayService does.
            //
            // Drawing nothing does not by itself put the transform outside the replay contract: with
            // sticky or walking wilds `carriedWilds` reads state off the session, so those rounds are
            // NOT a function of their own draws and ReplayService refuses them outright
            // (ROUND_NOT_REPLAYABLE) instead of reporting a mismatch. Capturing that carry per round -
            // as feature_context already does for respins - is what would make them replayable.
            WildFeatureEngine.WildOutcome wilds = wildFeatureEngine.apply(drawn.matrix(), math, stripSet,
                    carriedWilds(session, math));
            GridGenerationResult grid = new GridGenerationResult(wilds.matrix(), drawn.stopPositions());
            // The whole round, not just the opening board: a cascading game tumbles here, drawing its
            // refills from the same `rng` (and therefore the same sink) so the round stays replayable.
            EvaluationResult evaluation = reelEvaluator.evaluateRound(grid.matrix(), grid.stopPositions(),
                    effectiveBet, math, stripSet, rng);

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
                    grid, effectiveBet, request.powerBetActive(), rng);
            reasonCodes.addAll(post.reasonCodes());
            SessionState newState = post.newState();

            if (post.creditAmount() != null && post.creditAmount().signum() > 0) {
                winTxId = roundId + ":win";
                executeCredit(playerId, session, roundId, winTxId,
                        Money.of(post.creditAmount(), session.getCurrency()),
                        post.creditType());
                totalWin = post.creditAmount();
            }

            reasonCodes.addAll(wilds.reasonCodes());

            applyStateToSession(session, newState, effectiveBet, post.bonusBuyExecuted());
            // Sticky/walking wilds outlive the spin that drew them, so they ride alongside whatever
            // else the new state put in the payload. Only meaningful while a feature is running: the
            // BaseGame branch of applyStateToSession clears the payload, which ends the carry.
            persistCarriedWilds(session, wilds.carryForward());
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
                    .cascadeSteps(cascadeStepViews(evaluation))
                    .featuresTriggered(SpinResponse.FeaturesTriggered.builder()
                            .freeSpinsAwarded(post.freeSpinsAwarded())
                            .isPowerBetActive(request.powerBetActive())
                            .pickCollectTriggered(post.pickCollectTriggered())
                            .bonusBuyExecuted(post.bonusBuyExecuted())
                            .respinTriggered(post.respinTriggered())
                            .reasonCodes(reasonCodes)
                            .build())
                    .respinView(respinViewIfActive(saved, math))
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

    // ---------------------------------------------------------------- /spin (Hold & Spin respin)

    /**
     * One Hold &amp; Spin respin. The locked coins stay put, every other cell is re-drawn, and any coin
     * that lands is locked and refills the respin counter. The feature settles - crediting the coin
     * total plus whatever jackpot tier the final coin count earned - when the counter runs out or the
     * grid fills.
     *
     * <p>No debit: the round that triggered the feature already paid for it. That is enforced by the
     * FSM ({@code fromRespinLoop} emits {@link MonetaryEffect#none()}), not by this method choosing not
     * to charge.
     */
    private SpinResponse respinSpin(SpinRequest request, String playerId, GameSession session,
                                    SlotMathDefinition math, SessionState.RespinLoop currentState) {
        BigDecimal triggerBet = currentState.triggerBet();
        TransitionContext ctx = new TransitionContext(math, session.getCurrency());
        stateMachine.transition(currentState,
                new SessionCommand.SpinCommand(triggerBet, request.powerBetActive()), ctx);

        String roundId = "rsp-" + UUID.randomUUID();
        RngDrawSink sink = RngDrawSink.inMemory();
        RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);

        RespinState before = loadRespinState(session);
        RespinEngine.RespinOutcome outcome = respinEngine.respin(before, math, ReelStripSet.BASE, rng);

        List<String> reasonCodes = new ArrayList<>(outcome.reasonCodes());
        BigDecimal totalWin = BigDecimal.ZERO;
        String winTxId = null;
        RespinState after = outcome.state();
        SessionState newState;

        if (outcome.finished()) {
            RespinEngine.Settlement settlement = respinEngine.settle(after, math, triggerBet,
                    session.getCurrency());
            after = settlement.state();
            reasonCodes.addAll(settlement.reasonCodes());
            if (settlement.win().amount().signum() > 0) {
                winTxId = roundId + ":win";
                executeCredit(playerId, session, roundId, winTxId, settlement.win(),
                        WalletTransactionType.FEATURE_WIN);
                totalWin = settlement.win().amount();
            }
            newState = new SessionState.BaseGame();
        } else {
            newState = new SessionState.RespinLoop(serializeRespinPayload(after), triggerBet);
        }

        applyStateToSession(session, newState, triggerBet, false);
        session.setUpdatedAt(Instant.now());
        GameSession saved = sessionStore.save(session);

        // A respin is a round in its own right: it consumed draws, so it needs a row to replay from.
        // It carries no bet transaction, which is also how the free-spin iterations record.
        GameRound round = persistRespinRound(session, roundId, triggerBet,
                Money.of(totalWin, session.getCurrency()), before, outcome, sink, reasonCodes, winTxId,
                newState.gameState());

        return SpinResponse.builder()
                .sessionId(saved.getSessionId())
                .sessionVersion(saved.getSessionVersion())
                .roundId(round.getRoundId())
                .mathVersion(saved.getMathVersion())
                .betDebited(BigDecimal.ZERO)
                .totalWin(totalWin)
                .matrix(outcome.matrix())
                .stopPositions(new int[0])
                .winLines(List.of())
                .respinView(RespinFeatureView.of(after, math.respins(),
                        math.grid().rows(), math.grid().cols()))
                .featuresTriggered(SpinResponse.FeaturesTriggered.builder()
                        .freeSpinsAwarded(0)
                        .isPowerBetActive(false)
                        .pickCollectTriggered(false)
                        .bonusBuyExecuted(false)
                        .respinTriggered(false)
                        .reasonCodes(reasonCodes)
                        .build())
                .sessionState(SpinResponse.SessionStateView.builder()
                        .currentState(saved.getCurrentState())
                        .remainingFreeSpins(saved.getRemainingFreeSpins())
                        .accumulatedFreeSpinsWin(saved.getAccumulatedFreeSpinsWin())
                        .build())
                .availableActions(transitionActions(newState, math, List.of()))
                .build();
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
                case "RESPIN" -> new SessionCommand.StartRespinCommand();
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
                    .respinView(respinViewIfActive(saved, math))
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
            // Same rounding the FSM applies to the debit, so the charge, the recorded purchase event
            // and the amount reported back to the player are one number.
            BigDecimal cost = SessionStateMachine.buyCost(request.betSize(), option,
                    session.getCurrency());

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
            RespinState respinState = null;
            if (option.targetState() == GameState.RESPIN_AWAITING) {
                // The FSM established the purchase; the coins themselves need an RNG, so they are
                // drawn here - and, like every other mutation on this path, through the round's sink
                // so the bought feature is as replayable as a triggered one.
                RngDrawSink buySink = RngDrawSink.inMemory();
                respinState = respinEngine.startBought(math,
                        boughtCoinCount(math, option), new SecureRandomNumberGenerator(buySink));
                newState = new SessionState.RespinAwaiting(
                        serializeRespinPayload(respinState), request.betSize());
            }
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
            // A bought free-spins round is made richer per spin (not longer): stash its win multiplier so
            // the FREE_SPINS_LOOP settlement can boost the payout. Survives via the preserved feature
            // payload (see applyStateToSession); organic free spins never carry it.
            if (option.targetState() == GameState.FREE_SPINS_AWAITING
                    && option.freeSpinsWinMultiplier().compareTo(BigDecimal.ONE) > 0) {
                session.setActiveFeaturePayload(serializeToJson(Map.of(
                        FREE_SPINS_WIN_MULTIPLIER_KEY, option.freeSpinsWinMultiplier().toPlainString())));
            }
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
                    .remainingFreeSpins(saved.getRemainingFreeSpins())
                    .featureInitPayload(option.initialFeaturePayload())
                    .activeFeatureView(pcState != null ? PickCollectFeatureView.of(pcState) : null)
                    .respinView(respinState == null ? null
                            : RespinFeatureView.of(respinState, math.respins(),
                                    math.grid().rows(), math.grid().cols()))
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
        // Money values round-trip through NUMERIC(19,4) columns, so they come back with scale 4.
        // Re-normalize to currency minor units before they flow back into Money, which rejects scale > 2.
        BigDecimal bet = toCurrencyScale(session.getCurrentBet(), session.getCurrency());
        BigDecimal accumulated = toCurrencyScale(session.getAccumulatedFreeSpinsWin(), session.getCurrency());
        return switch (session.getCurrentState()) {
            case BASE_GAME -> new SessionState.BaseGame();
            case FREE_SPINS_AWAITING -> new SessionState.FreeSpinsAwaiting(
                    Math.max(1, session.getRemainingFreeSpins()), bet);
            case FREE_SPINS_LOOP -> new SessionState.FreeSpinsLoop(
                    session.getRemainingFreeSpins(), accumulated, bet);
            case PICK_COLLECT_AWAITING -> new SessionState.PickCollectAwaiting(
                    deserializeMap(session.getActiveFeaturePayload()));
            case PICK_COLLECT_LOOP -> new SessionState.PickCollectLoop(
                    deserializeMap(session.getActiveFeaturePayload()));
            case RESPIN_AWAITING -> new SessionState.RespinAwaiting(
                    deserializeMap(session.getActiveFeaturePayload()), bet);
            case RESPIN_LOOP -> new SessionState.RespinLoop(
                    deserializeMap(session.getActiveFeaturePayload()), bet);
        };
    }

    /** Brings a persisted money value back to the currency's minor-unit scale (trailing zeros only). */
    private static BigDecimal toCurrencyScale(BigDecimal value, String currency) {
        if (value == null) {
            return null;
        }
        return value.setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP);
    }

    private BigDecimal effectiveBetForSpin(SessionState state, BigDecimal requestedBet,
                                           boolean powerBetActive, SlotMathDefinition math,
                                           String currency) {
        if (state instanceof SessionState.FreeSpinsLoop loop) {
            BigDecimal locked = math.freeSpins().betLockedToTriggerBet() ? loop.triggerBet() : requestedBet;
            return locked;
        }
        // Power Bet raises the actual stake by powerBet.betMultiplier (e.g. ×1.5). This single
        // effective bet drives the wallet debit (via the FSM), the win evaluation, and the persisted
        // round stake, so RTP stays consistent across all three. Only the base game opts into it -
        // free spins already returned above and bonus-buy flows have their own cost path.
        if (state instanceof SessionState.BaseGame && powerBetActive) {
            return requestedBet.multiply(math.powerBet().betMultiplier())
                    .setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP);
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
                                               boolean powerBetActive, RandomNumberGenerator rng) {
        int scatterCount = countScatters(grid.matrix(), math);
        List<String> reasonCodes = new ArrayList<>();
        BigDecimal creditAmount = BigDecimal.ZERO;
        WalletTransactionType creditType = WalletTransactionType.WIN;
        SessionState newState = afterFsm;
        int freeSpinsAwarded = 0;
        boolean pickCollectTriggered = false;
        boolean respinTriggered = false;

        if (afterFsm instanceof SessionState.BaseGame) {
            if (evaluation.totalWin().signum() > 0) {
                creditAmount = evaluation.totalWin();
                creditType = WalletTransactionType.WIN;
            }
            // Hold & Spin outranks the scatter award: enough coins on screen is a rarer and richer
            // event than a scatter trigger, and the two are mutually exclusive on one spin.
            if (respinEngine.triggers(grid.matrix(), math.respins())) {
                RespinState opening = respinEngine.start(grid.matrix(), math.respins(), rng);
                newState = new SessionState.RespinAwaiting(serializeRespinPayload(opening), bet);
                respinTriggered = true;
                reasonCodes.add(RespinEngine.REASON_TRIGGERED);
            } else if (scatterCount >= math.scatterTriggers().minCount()) {
                freeSpinsAwarded = math.scatterTriggers().freeSpinsAwarded();
                newState = new SessionState.FreeSpinsAwaiting(freeSpinsAwarded, bet);
                reasonCodes.add("TRIGGERED_BY_SCATTER");
            } else if (rollPickCollectTrigger(math, rng)) {
                // Organic Pick & Collect trigger (1 in triggerOneInN), mutually exclusive with a
                // free-spins award on the same spin. The board is generated later when the player
                // begins the feature via /feature/start, mirroring the free-spins awaiting flow.
                newState = new SessionState.PickCollectAwaiting(Map.of(
                        "boardSize", math.pickCollect().boardSize(),
                        "trigger", "ORGANIC"));
                pickCollectTriggered = true;
                reasonCodes.add("PICK_COLLECT_TRIGGERED");
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
                // A bought free-spins round pays a per-spin-equivalent boost applied to the whole feature
                // win at settlement (the marker is set at purchase and cleared when we return to base).
                // Organic free spins carry no marker, so the multiplier is 1. Credit and the persisted
                // round win are set together below, keeping wallet credit == round.totalWin.
                BigDecimal multiplier = boughtFreeSpinsMultiplier(session);
                creditAmount = acc.multiply(multiplier)
                        .setScale(Money.minorUnitScale(session.getCurrency()), RoundingMode.HALF_UP);
                creditType = WalletTransactionType.FEATURE_WIN;
                reasonCodes.add("FREE_SPINS_SETTLED");
                if (multiplier.compareTo(BigDecimal.ONE) > 0) {
                    reasonCodes.add("BONUS_BUY_MULTIPLIER_APPLIED");
                }
                newState = new SessionState.BaseGame();
            } else {
                newState = new SessionState.FreeSpinsLoop(remaining, acc, loop.triggerBet());
            }
        }
        return new SpinPostProcessing(newState, creditAmount, creditType, reasonCodes,
                freeSpinsAwarded, pickCollectTriggered, respinTriggered, false);
    }

    /**
     * One per-spin draw of the organic Pick &amp; Collect trigger ({@code 1 in triggerOneInN}). The draw
     * is taken from the spin RNG so it is captured in the round's draw log and replays deterministically.
     */
    private boolean rollPickCollectTrigger(SlotMathDefinition math, RandomNumberGenerator rng) {
        if (!math.pickCollect().organicTriggerEnabled()) {
            return false;
        }
        return rng.nextIndex(math.pickCollect().triggerOneInN()) == 0;
    }

    /**
     * Win multiplier for the currently active free-spins round, read from the bought-feature marker in
     * {@code active_feature_payload}. Returns {@code 1} for organically triggered free spins (no marker)
     * or any unparseable/legacy payload.
     */
    private BigDecimal boughtFreeSpinsMultiplier(GameSession session) {
        String payload = session.getActiveFeaturePayload();
        if (payload == null || payload.isBlank()) {
            return BigDecimal.ONE;
        }
        try {
            Object raw = deserializeMap(payload).get(FREE_SPINS_WIN_MULTIPLIER_KEY);
            return raw == null ? BigDecimal.ONE : new BigDecimal(raw.toString());
        } catch (RuntimeException ex) {
            return BigDecimal.ONE;
        }
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
            log.error("credit failed - issuing rollback of original bet player={} txId={} cause={}",
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
                // Preserve the feature payload across the free-spins lifecycle: a bought feature stores its
                // win-multiplier marker here at purchase, and it must survive START_FREE_SPINS and every
                // loop spin until settlement (where the BaseGame branch clears it). Organic free spins
                // arrive from the base game with a null payload, so this is a no-op for them.
                session.setNextActionAllowed(GameCommand.START_FREE_SPINS.name());
            }
            case SessionState.FreeSpinsLoop loop -> {
                session.setRemainingFreeSpins(loop.remainingFreeSpins());
                session.setAccumulatedFreeSpinsWin(loop.accumulatedWin());
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
            // The locked coins and the respin counter live entirely in active_feature_payload, so a
            // Hold & Spin feature survives every request boundary the same way Pick & Collect's board
            // does - and, critically, survives a session rehydrated from Postgres after a cache miss.
            case SessionState.RespinAwaiting awaiting -> {
                session.setRemainingFreeSpins(0);
                session.setAccumulatedFreeSpinsWin(BigDecimal.ZERO);
                session.setActiveFeaturePayload(serializeToJson(awaiting.featurePayload()));
                session.setNextActionAllowed(GameCommand.START_RESPIN.name());
            }
            case SessionState.RespinLoop loop -> {
                session.setRemainingFreeSpins(0);
                session.setAccumulatedFreeSpinsWin(BigDecimal.ZERO);
                session.setActiveFeaturePayload(serializeToJson(loop.featurePayload()));
                session.setNextActionAllowed(GameCommand.SPIN.name());
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
            case RESPIN_AWAITING -> List.of(GameCommand.START_RESPIN);
            case RESPIN_LOOP -> List.of(GameCommand.SPIN);
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
            case SessionState.RespinAwaiting ignored -> List.of(GameCommand.START_RESPIN);
            case SessionState.RespinLoop ignored -> List.of(GameCommand.SPIN);
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
        round.setRoundKind(RoundKind.SPIN);
        round.setBetAmount(betAmount);
        round.setBetAmountMinor(Money.of(betAmount, session.getCurrency()).toMinor());
        round.setTotalWin(totalWin.amount());
        round.setTotalWinMinor(totalWin.toMinor());
        round.setCurrency(session.getCurrency());
        // The whole drop sequence, not just the opening board - a cascading round is only replayable if
        // every intermediate grid is on the row alongside the draws that produced it. Non-cascading
        // rounds still write the flat single-grid shape (see RoundGridCodec).
        round.setMatrix(gridCodec.writeMatrices(evaluation.steps()));
        round.setStopPositions(gridCodec.writeStopPositions(evaluation.steps()));
        round.setRngDraws(serializeToJson(sink.drawn()));
        round.setWinLines(serializeToJson(evaluation.winLines()));
        round.setReasonCodes(serializeToJson(reasonCodes));
        round.setPowerBetActive(powerBetActive);
        round.setBetTransactionId(betTxId);
        round.setWinTransactionId(winTxId);
        round.setCreatedAt(Instant.now());
        return roundRepository.save(round);
    }

    /**
     * The round's drop sequence as the client animates it, or {@code null} when the round settled in one
     * drop. Omitting the field for conventional spins keeps their payload byte-identical to before
     * cascades existed - the opening {@code matrix} already says everything there is to say.
     */
    private List<SpinResponse.CascadeStepView> cascadeStepViews(EvaluationResult evaluation) {
        if (!evaluation.cascaded()) {
            return null;
        }
        return evaluation.steps().stream()
                .map(step -> SpinResponse.CascadeStepView.builder()
                        .index(step.index())
                        .matrix(step.grid())
                        .winLines(step.winLines())
                        .multiplier(step.multiplier())
                        .stepWin(step.stepWin())
                        .clearedPositions(step.clearedPositions())
                        .build())
                .toList();
    }

    // ------------------------------------------------------- sticky wild carry-over

    /** Key under which sticky/walking wilds ride in {@code active_feature_payload}. */
    private static final String STICKY_WILDS_KEY = "stickyWilds";

    /**
     * Wilds held over from the previous spin of the running feature. Empty in the base game and on the
     * first spin of a feature, which is exactly right - nothing has stuck yet.
     */
    @SuppressWarnings("unchecked")
    private List<WildFeatureEngine.WildCell> carriedWilds(GameSession session, SlotMathDefinition math) {
        if (!math.wildFeatures().active()) {
            return List.of();
        }
        String payload = session.getActiveFeaturePayload();
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            Object raw = deserializeMap(payload).get(STICKY_WILDS_KEY);
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            List<WildFeatureEngine.WildCell> cells = new ArrayList<>(list.size());
            for (Object entry : list) {
                Map<String, Object> cell = (Map<String, Object>) entry;
                cells.add(new WildFeatureEngine.WildCell(
                        ((Number) cell.get("row")).intValue(),
                        ((Number) cell.get("col")).intValue(),
                        ((Number) cell.get("remainingSpins")).intValue()));
            }
            return cells;
        } catch (RuntimeException ex) {
            // A payload written before sticky wilds existed simply has no entry; treat anything
            // unreadable the same way rather than failing a spin over a presentation detail.
            return List.of();
        }
    }

    /**
     * Merges the surviving sticky wilds into whatever {@code applyStateToSession} just wrote, so the
     * carry never clobbers a feature's own payload (a Pick &amp; Collect board, a respin's coins).
     */
    private void persistCarriedWilds(GameSession session, List<WildFeatureEngine.WildCell> wilds) {
        String payload = session.getActiveFeaturePayload();
        if (wilds.isEmpty()) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(
                payload == null || payload.isBlank() ? Map.of() : deserializeMap(payload));
        merged.put(STICKY_WILDS_KEY, wilds.stream()
                .map(w -> Map.<String, Object>of(
                        "row", w.row(),
                        "col", w.col(),
                        "remainingSpins", w.remainingSpins()))
                .toList());
        session.setActiveFeaturePayload(serializeToJson(merged));
    }

    // ------------------------------------------------------- Hold & Spin payload round-trip

    private Map<String, Object> serializeRespinPayload(RespinState state) {
        return respinPayloadCodec.encode(state);
    }

    private RespinState loadRespinState(GameSession session) {
        String payloadJson = session.getActiveFeaturePayload();
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Respin feature payload missing for session " + session.getSessionId());
        }
        return respinPayloadCodec.decode(deserializeMap(payloadJson));
    }

    /** The live Hold &amp; Spin view, or {@code null} when the session is not in the feature. */
    private RespinFeatureView respinViewIfActive(GameSession session, SlotMathDefinition math) {
        if (session.getCurrentState() != GameState.RESPIN_AWAITING
                && session.getCurrentState() != GameState.RESPIN_LOOP) {
            return null;
        }
        return RespinFeatureView.of(loadRespinState(session), math.respins(),
                math.grid().rows(), math.grid().cols());
    }

    /**
     * Persists one respin iteration. It carries no bet transaction (the triggering round paid) and no
     * win lines (coins pay by value, not by line).
     *
     * <p>Crucially it also carries {@code feature_context}: the coins held <em>before</em> this respin.
     * A respin re-draws only the unlocked cells, so its draws are meaningless without knowing which
     * cells those were - the count and the strip bounds both depend on it. Persisting the input state
     * alongside the draws is what makes a respin as independently replayable as a spin.
     */
    private GameRound persistRespinRound(GameSession session, String roundId, BigDecimal betAmount,
                                         Money totalWin, RespinState heldBefore,
                                         RespinEngine.RespinOutcome outcome,
                                         RngDrawSink sink, List<String> reasonCodes, String winTxId,
                                         GameState afterState) {
        GameRound round = new GameRound();
        round.setSessionId(session.getSessionId());
        round.setPlayerId(session.getPlayerId());
        round.setRoundId(roundId);
        round.setGameId(session.getGameId());
        round.setMathVersion(session.getMathVersion());
        round.setStateContext(afterState);
        round.setRoundKind(RoundKind.RESPIN);
        round.setFeatureContext(serializeToJson(serializeRespinPayload(heldBefore)));
        round.setBetAmount(betAmount);
        round.setBetAmountMinor(Money.of(betAmount, session.getCurrency()).toMinor());
        round.setTotalWin(totalWin.amount());
        round.setTotalWinMinor(totalWin.toMinor());
        round.setCurrency(session.getCurrency());
        round.setMatrix(serializeToJson(outcome.matrix()));
        round.setStopPositions(serializeToJson(new int[0]));
        round.setRngDraws(serializeToJson(sink.drawn()));
        round.setWinLines(serializeToJson(List.of()));
        round.setReasonCodes(serializeToJson(reasonCodes));
        round.setPowerBetActive(false);
        round.setBetTransactionId(null);
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

    /**
     * How many coins a bought Hold &amp; Spin starts with, from the option's
     * {@code initialFeaturePayload.coins}. Falls back to the organic trigger count, which is the
     * honest default: buying the feature should hand over what landing it would have.
     */
    private int boughtCoinCount(SlotMathDefinition math, BonusBuyOption option) {
        Object raw = option.initialFeaturePayload().get("coins");
        return raw instanceof Number n ? n.intValue() : math.respins().triggerMinCount();
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
            boolean respinTriggered,
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
