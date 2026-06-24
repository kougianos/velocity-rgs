package com.velocity.rgs.blackjack.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.blackjack.api.BlackjackActionRequest;
import com.velocity.rgs.blackjack.api.BlackjackDealRequest;
import com.velocity.rgs.blackjack.api.BlackjackInitRequest;
import com.velocity.rgs.blackjack.api.BlackjackInitResponse;
import com.velocity.rgs.blackjack.api.BlackjackRoundResponse;
import com.velocity.rgs.blackjack.config.BlackjackCatalogRegistry;
import com.velocity.rgs.blackjack.config.BlackjackMathDefinition;
import com.velocity.rgs.blackjack.domain.BlackjackAction;
import com.velocity.rgs.blackjack.domain.BlackjackRound;
import com.velocity.rgs.blackjack.domain.DealerState;
import com.velocity.rgs.blackjack.domain.HandState;
import com.velocity.rgs.blackjack.domain.HandStatus;
import com.velocity.rgs.blackjack.domain.InsuranceState;
import com.velocity.rgs.blackjack.domain.RoundContext;
import com.velocity.rgs.blackjack.domain.RoundStatus;
import com.velocity.rgs.blackjack.domain.ShoeState;
import com.velocity.rgs.blackjack.engine.BlackjackActions;
import com.velocity.rgs.blackjack.engine.BlackjackDealer;
import com.velocity.rgs.blackjack.engine.BlackjackSettlement;
import com.velocity.rgs.blackjack.engine.HandSettlement;
import com.velocity.rgs.blackjack.persistence.BlackjackRoundRepository;
import com.velocity.rgs.card.Card;
import com.velocity.rgs.card.HandValue;
import com.velocity.rgs.card.Shoe;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.rng.RandomNumberGenerator;
import com.velocity.rgs.rng.RngDrawSink;
import com.velocity.rgs.rng.SecureRandomNumberGenerator;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Application-layer orchestrator for the public blackjack endpoints. Composes the math registry, the
 * {@link BlackjackDealer}/{@link BlackjackSettlement}/{@link BlackjackActions} engine (all game logic), the
 * {@link WalletGateway} (money), the shared {@link SessionStore} (identity) and the {@link PlayerActionLock}
 * (concurrency).
 *
 * <p>Unlike the one-shot slot/roulette engines, a blackjack round is <b>stateful and multi-step</b>: the
 * canonical state lives in a {@code blackjack_round} row (a shuffled shoe + draw cursor + the hands), written
 * {@code IN_PROGRESS} at the deal and mutated through each action until it settles. The active round for a
 * session is the single {@code IN_PROGRESS} row. The dealer's hole card is held server-side but never leaves
 * the server until the round settles. Credit-failure rolls back the round's debits, mirroring the roulette
 * engine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlackjackEngineService {

    private final BlackjackCatalogRegistry catalog;
    private final BlackjackDealer dealer;
    private final BlackjackSettlement settlement;
    private final BlackjackActions actions;
    private final WalletGateway walletGateway;
    private final SessionStore sessionStore;
    private final PlayerActionLock actionLock;
    private final BlackjackRoundRepository roundRepository;
    private final ObjectMapper objectMapper;

    private static final List<BlackjackAction> DEAL_ONLY = List.of(BlackjackAction.DEAL);
    private static final TypeReference<List<HandState>> HANDS_TYPE = new TypeReference<>() {};

    // ---------------------------------------------------------------- /init

    @Transactional
    public BlackjackInitResponse init(BlackjackInitRequest request, String playerId, String currency) {
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

        BlackjackMathDefinition math = catalog.require(session.getGameId(), session.getMathVersion()).math();
        BigDecimal balance = auth.balance();

        // Resume an unsettled round (e.g. a mid-hand reload) so the client can continue exactly where it was.
        Optional<BlackjackRound> active = roundRepository
                .findFirstBySessionIdAndStatusOrderByCreatedAtDesc(session.getSessionId(), RoundStatus.IN_PROGRESS);
        BlackjackRoundResponse activeRound = active
                .map(round -> buildResponse(round, loadWork(round), session, math, balance))
                .orElse(null);

        return BlackjackInitResponse.builder()
                .sessionId(session.getSessionId())
                .sessionVersion(session.getSessionVersion())
                .gameId(session.getGameId())
                .mathVersion(session.getMathVersion())
                .currency(session.getCurrency())
                .balance(balance)
                .betValues(math.betConfig().values())
                .defaultBet(math.betConfig().defaultBet())
                .maxBet(math.limits().maxBet())
                .rules(BlackjackInitResponse.RulesView.builder()
                        .decks(math.decks())
                        .dealerHitsSoft17(math.dealerHitsSoft17())
                        .blackjackPayout(math.blackjackPayout())
                        .doubleAfterSplit(math.doubleAfterSplit())
                        .maxSplits(math.maxSplits())
                        .insuranceEnabled(math.insuranceEnabled())
                        .insurancePayout(math.insurancePayout())
                        .build())
                .availableActions(activeRound != null ? activeRound.availableActions() : DEAL_ONLY)
                .activeRound(activeRound)
                .build();
    }

    // ---------------------------------------------------------------- /deal

    @Transactional
    public BlackjackRoundResponse deal(BlackjackDealRequest request, String playerId) {
        LockHandle handle = actionLock.acquire(playerId);
        try {
            GameSession session = requireSession(request.sessionId(), playerId, request.gameId(),
                    request.sessionVersion());
            BlackjackMathDefinition math = catalog.require(session.getGameId(), session.getMathVersion()).math();
            String currency = session.getCurrency();

            if (roundRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(
                    session.getSessionId(), RoundStatus.IN_PROGRESS).isPresent()) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "A round is already in progress for this session — finish it before dealing again");
            }

            BigDecimal bet = normalize(request.bet(), currency);
            if (!math.betConfig().isValidBet(bet)) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Bet " + bet + " is not an allowed stake for game " + math.gameId());
            }
            if (bet.compareTo(math.limits().maxBet()) > 0) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "Bet " + bet + " exceeds table limit " + math.limits().maxBet());
            }

            String roundId = "blk-" + UUID.randomUUID();
            RngDrawSink sink = RngDrawSink.inMemory();
            RandomNumberGenerator rng = new SecureRandomNumberGenerator(sink);

            // 1. Debit the base stake up front.
            String betTxId = roundId + ":bet";
            executeDebit(playerId, session, roundId, betTxId, Money.of(bet, currency));

            // 2. Shuffle a fresh shoe and deal player 2 + dealer 2 (upcard then hole, standard alternating).
            Shoe shoe = Shoe.shuffled(math.decks(), rng);
            Card p1 = shoe.draw();
            Card up = shoe.draw();
            Card p2 = shoe.draw();
            Card hole = shoe.draw();

            HandState hand = HandState.of(bet, p1, p2);
            DealerState dealerState = DealerState.of(up, hole);
            List<HandState> hands = new ArrayList<>(List.of(hand));
            RoundContext ctx = new RoundContext();
            ctx.setActiveHandIndex(0);

            boolean playerBlackjack = hand.handValue().isBlackjack();
            if (playerBlackjack) {
                hand.setStatus(HandStatus.BLACKJACK);
            }

            BlackjackRound round = newRound(session, roundId, math, currency, bet, hands, dealerState, shoe,
                    ctx, sink.drawn(), betTxId);

            boolean upcardAce = up.rank().isAce();
            boolean upcardTen = up.value() == 10;

            // 3a. Dealer shows an Ace and insurance is on: offer it and defer the peek to the first action.
            if (upcardAce && math.insuranceEnabled()) {
                ctx.setInsuranceOffered(true);
                BlackjackRound saved = persist(round, hands, dealerState, shoe, ctx,
                        round.getTotalBet(), BigDecimal.ZERO, null, RoundStatus.IN_PROGRESS);
                return respond(saved, session, math);
            }

            // 3b. Peek for a dealer natural when the upcard could make one (Ace or ten-value).
            boolean dealerBlackjack = dealerState.handValue().isBlackjack();
            if ((upcardAce || upcardTen) && dealerBlackjack) {
                return settleAndRespond(round, session, math, hands, dealerState, shoe, ctx, false);
            }

            // 3c. Player natural (dealer cannot have one here): pay 3:2 immediately, dealer does not draw.
            if (playerBlackjack) {
                return settleAndRespond(round, session, math, hands, dealerState, shoe, ctx, false);
            }

            // 3d. Normal play.
            BlackjackRound saved = persist(round, hands, dealerState, shoe, ctx,
                    round.getTotalBet(), BigDecimal.ZERO, null, RoundStatus.IN_PROGRESS);
            return respond(saved, session, math);
        } finally {
            actionLock.release(handle);
        }
    }

    // ---------------------------------------------------------------- /action

    @Transactional
    public BlackjackRoundResponse action(BlackjackActionRequest request, String playerId) {
        LockHandle handle = actionLock.acquire(playerId);
        try {
            GameSession session = requireSession(request.sessionId(), playerId, request.gameId(),
                    request.sessionVersion());
            BlackjackMathDefinition math = catalog.require(session.getGameId(), session.getMathVersion()).math();
            String currency = session.getCurrency();
            BlackjackAction act = parseAction(request.action());

            BlackjackRound round = roundRepository
                    .findFirstBySessionIdAndStatusOrderByCreatedAtDesc(session.getSessionId(), RoundStatus.IN_PROGRESS)
                    .orElseThrow(() -> new RgsException(ErrorCode.VALIDATION_ERROR,
                            "No round in progress for this session"));

            RoundWork work = loadWork(round);
            List<HandState> hands = work.hands();
            DealerState dealerState = work.dealer();
            Shoe shoe = work.shoe();
            RoundContext ctx = work.context();

            // ---- Insurance phase: the dealer shows an Ace and the player has not yet decided. ----
            if (ctx.isInsuranceOffered()) {
                if (act == BlackjackAction.INSURANCE) {
                    return takeInsurance(round, session, math, currency, playerId, hands, dealerState, shoe, ctx);
                }
                // Any other action declines insurance — peek now, then either settle or play on.
                ctx.setInsuranceOffered(false);
                if (resolvePeek(dealerState, hands)) {
                    return settleAndRespond(round, session, math, hands, dealerState, shoe, ctx, false);
                }
                ctx.setActiveHandIndex(0);
                // fall through to apply the chosen action to the first hand
            } else if (act == BlackjackAction.INSURANCE) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR, "Insurance is not available now");
            }

            // ---- Normal action on the active hand. ----
            int idx = ctx.getActiveHandIndex();
            if (idx < 0 || idx >= hands.size() || hands.get(idx).getStatus() != HandStatus.ACTIVE) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR, "No active hand to act on");
            }
            if (request.handIndex() != null && request.handIndex() != idx) {
                throw new RgsException(ErrorCode.VALIDATION_ERROR,
                        "handIndex " + request.handIndex() + " is not the active hand " + idx);
            }
            HandState active = hands.get(idx);

            switch (act) {
                case HIT -> hitHand(active, shoe);
                case STAND -> active.setStatus(HandStatus.STAND);
                case DOUBLE -> doubleHand(round, session, math, currency, playerId, active, shoe, ctx);
                case SPLIT -> splitHand(round, session, math, currency, playerId, hands, idx, shoe, ctx);
                default -> throw new RgsException(ErrorCode.VALIDATION_ERROR, "Illegal action: " + act);
            }

            advance(hands, ctx);
            if (ctx.getActiveHandIndex() < 0) {
                return settleAndRespond(round, session, math, hands, dealerState, shoe, ctx, true);
            }
            BlackjackRound saved = persist(round, hands, dealerState, shoe, ctx,
                    round.getTotalBet(), BigDecimal.ZERO, null, RoundStatus.IN_PROGRESS);
            return respond(saved, session, math);
        } finally {
            actionLock.release(handle);
        }
    }

    // ===================================================================== action helpers

    private void hitHand(HandState active, Shoe shoe) {
        active.addCard(shoe.draw());
        resolveAfterCard(active);
    }

    private void doubleHand(BlackjackRound round, GameSession session, BlackjackMathDefinition math,
                            String currency, String playerId, HandState active, Shoe shoe, RoundContext ctx) {
        if (active.cardList().size() != 2) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR, "Double is only allowed on a two-card hand");
        }
        if (active.isFromSplit() && !math.doubleAfterSplit()) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR, "Double after split is not allowed");
        }
        BigDecimal extra = active.getBet();
        String txId = nextBetTxId(round, ctx, "double");
        executeDebit(playerId, session, round.getRoundId(), txId, Money.of(extra, currency));
        round.setTotalBet(round.getTotalBet().add(extra));
        active.setBet(active.getBet().add(extra));
        active.setDoubled(true);
        active.addCard(shoe.draw());
        active.setStatus(active.handValue().isBust() ? HandStatus.BUST : HandStatus.DOUBLED);
    }

    private void splitHand(BlackjackRound round, GameSession session, BlackjackMathDefinition math,
                           String currency, String playerId, List<HandState> hands, int idx, Shoe shoe,
                           RoundContext ctx) {
        HandState orig = hands.get(idx);
        List<Card> cards = orig.cardList();
        if (cards.size() != 2 || !HandValue.isSplittablePair(cards)) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR, "Split requires a matching pair");
        }
        if (hands.size() >= math.maxHands()) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR,
                    "Cannot split beyond " + math.maxHands() + " hands");
        }
        BigDecimal extra = orig.getBet();
        String txId = nextBetTxId(round, ctx, "split");
        executeDebit(playerId, session, round.getRoundId(), txId, Money.of(extra, currency));
        round.setTotalBet(round.getTotalBet().add(extra));

        boolean aces = cards.get(0).rank().isAce();
        HandState a = HandState.of(orig.getBet(), cards.get(0));
        HandState b = HandState.of(orig.getBet(), cards.get(1));
        a.setFromSplit(true);
        b.setFromSplit(true);
        a.addCard(shoe.draw());
        b.addCard(shoe.draw());
        if (aces) {
            // Split aces receive exactly one card each and stand (and cannot be re-split).
            a.setSplitAce(true);
            a.setStatus(HandStatus.STAND);
            b.setSplitAce(true);
            b.setStatus(HandStatus.STAND);
        } else {
            resolveAfterCard(a);
            resolveAfterCard(b);
        }
        hands.set(idx, a);
        hands.add(idx + 1, b);
    }

    /** Take the insurance side bet (half the base bet), peek the hole, and settle or continue accordingly. */
    private BlackjackRoundResponse takeInsurance(BlackjackRound round, GameSession session,
                                                 BlackjackMathDefinition math, String currency, String playerId,
                                                 List<HandState> hands, DealerState dealerState, Shoe shoe,
                                                 RoundContext ctx) {
        BigDecimal insuranceBet = round.getBet()
                .divide(BigDecimal.valueOf(2), Money.minorUnitScale(currency), RoundingMode.HALF_UP);
        String txId = round.getRoundId() + ":insurance";
        executeDebit(playerId, session, round.getRoundId(), txId, Money.of(insuranceBet, currency));
        round.setTotalBet(round.getTotalBet().add(insuranceBet));

        boolean dealerBlackjack = dealerState.handValue().isBlackjack();
        InsuranceState insurance = InsuranceState.of(insuranceBet);
        insurance.setResolved(true);
        insurance.setWon(dealerBlackjack);
        insurance.setPayout(settlement.settleInsurance(insuranceBet, dealerBlackjack, math));
        ctx.setInsurance(insurance);
        ctx.setInsuranceOffered(false);

        // Dealer natural, or the player's own natural, ends the round immediately; otherwise play continues.
        if (dealerBlackjack || hands.get(0).getStatus() == HandStatus.BLACKJACK) {
            return settleAndRespond(round, session, math, hands, dealerState, shoe, ctx, false);
        }
        ctx.setActiveHandIndex(0);
        BlackjackRound saved = persist(round, hands, dealerState, shoe, ctx,
                round.getTotalBet(), BigDecimal.ZERO, null, RoundStatus.IN_PROGRESS);
        return respond(saved, session, math);
    }

    /** After declining insurance: returns true if the hole reveals a result that ends the round now. */
    private boolean resolvePeek(DealerState dealerState, List<HandState> hands) {
        return dealerState.handValue().isBlackjack() || hands.get(0).getStatus() == HandStatus.BLACKJACK;
    }

    private void resolveAfterCard(HandState hand) {
        HandValue value = hand.handValue();
        if (value.isBust()) {
            hand.setStatus(HandStatus.BUST);
        } else if (value.total() == 21) {
            hand.setStatus(HandStatus.STAND);
        }
    }

    private void advance(List<HandState> hands, RoundContext ctx) {
        for (int i = 0; i < hands.size(); i++) {
            if (hands.get(i).getStatus() == HandStatus.ACTIVE) {
                ctx.setActiveHandIndex(i);
                return;
            }
        }
        ctx.setActiveHandIndex(-1);
    }

    /**
     * A short, deterministic wallet transaction id for an additional in-round wager ({@code double}/
     * {@code split}). Uses a per-round counter so the id stays within the 64-char column and is idempotent on
     * retry — a random UUID both overflowed the column and risked a double-charge.
     */
    private String nextBetTxId(BlackjackRound round, RoundContext ctx, String kind) {
        ctx.setBetSeq(ctx.getBetSeq() + 1);
        return round.getRoundId() + ":" + kind + ":" + ctx.getBetSeq();
    }

    // ===================================================================== settlement

    /**
     * Finishes the round: the dealer plays out (only when {@code dealerPlays} and at least one player hand is
     * not bust), every hand is settled against the dealer's final total, the total return is credited, and the
     * round is persisted as {@code SETTLED}. Insurance, if taken, was already resolved at the peek.
     */
    private BlackjackRoundResponse settleAndRespond(BlackjackRound round, GameSession session,
                                                    BlackjackMathDefinition math, List<HandState> hands,
                                                    DealerState dealerState, Shoe shoe, RoundContext ctx,
                                                    boolean dealerPlays) {
        ctx.setDealerRevealed(true);
        ctx.setActiveHandIndex(-1);

        boolean anyLive = hands.stream().anyMatch(h -> h.getStatus() != HandStatus.BUST);
        if (dealerPlays && anyLive) {
            List<Card> finalDealer = dealer.play(dealerState.cardList(), shoe, math.dealerHitsSoft17());
            dealerState.setCards(new ArrayList<>(finalDealer.stream().map(Card::code).toList()));
        }
        List<Card> dealerFinal = dealerState.cardList();

        BigDecimal totalWin = BigDecimal.ZERO;
        for (HandState hand : hands) {
            boolean naturalBlackjack = hand.getStatus() == HandStatus.BLACKJACK;
            HandSettlement result = settlement.settleHand(
                    hand.cardList(), naturalBlackjack, hand.getBet(), dealerFinal, math);
            hand.setOutcome(result.outcome());
            hand.setPayout(result.payout());
            totalWin = totalWin.add(result.payout());
        }
        if (ctx.getInsurance() != null && ctx.getInsurance().isResolved()) {
            totalWin = totalWin.add(ctx.getInsurance().getPayout());
        }
        String currency = session.getCurrency();
        totalWin = totalWin.setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP);

        String winTxId = null;
        if (totalWin.signum() > 0) {
            winTxId = round.getRoundId() + ":win";
            executeCredit(session.getPlayerId(), session, round.getRoundId(), winTxId, Money.of(totalWin, currency));
        }

        BlackjackRound saved = persist(round, hands, dealerState, shoe, ctx,
                round.getTotalBet(), totalWin, winTxId, RoundStatus.SETTLED);
        return respond(saved, session, math);
    }

    // ===================================================================== persistence / mapping

    private BlackjackRound newRound(GameSession session, String roundId, BlackjackMathDefinition math,
                                    String currency, BigDecimal bet, List<HandState> hands, DealerState dealerState,
                                    Shoe shoe, RoundContext ctx, Object rngDraws, String betTxId) {
        Instant now = Instant.now();
        BlackjackRound round = BlackjackRound.builder()
                .sessionId(session.getSessionId())
                .playerId(session.getPlayerId())
                .roundId(roundId)
                .gameId(session.getGameId())
                .mathVersion(session.getMathVersion())
                .currency(currency)
                .bet(bet)
                .status(RoundStatus.IN_PROGRESS)
                .shoe(toJson(ShoeState.of(shoe)))
                .playerHands(toJson(hands))
                .dealerHand(toJson(dealerState))
                .outcomes(toJson(ctx))
                .totalBet(bet)
                .totalBetMinor(Money.of(bet, currency).toMinor())
                .totalWin(BigDecimal.ZERO)
                .totalWinMinor(0L)
                .rngDraws(toJson(rngDraws))
                .betTransactionId(betTxId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return round;
    }

    /** Write the working model back onto the round entity and save it (insert on first call, update after). */
    private BlackjackRound persist(BlackjackRound round, List<HandState> hands, DealerState dealerState, Shoe shoe,
                                   RoundContext ctx, BigDecimal totalBet, BigDecimal totalWin, String winTxId,
                                   RoundStatus status) {
        String currency = round.getCurrency();
        // total_bet/total_win are NUMERIC(19,4); reloading them across steps yields scale-4 BigDecimals,
        // so re-normalize to currency minor units before Money (which rejects scale > 2).
        int scale = Money.minorUnitScale(currency);
        BigDecimal bet = totalBet.setScale(scale, RoundingMode.HALF_UP);
        BigDecimal win = totalWin.setScale(scale, RoundingMode.HALF_UP);
        round.setShoe(toJson(ShoeState.of(shoe)));
        round.setPlayerHands(toJson(hands));
        round.setDealerHand(toJson(dealerState));
        round.setOutcomes(toJson(ctx));
        round.setTotalBet(bet);
        round.setTotalBetMinor(Money.of(bet, currency).toMinor());
        round.setTotalWin(win);
        round.setTotalWinMinor(Money.of(win, currency).toMinor());
        if (winTxId != null) {
            round.setWinTransactionId(winTxId);
        }
        round.setStatus(status);
        round.setUpdatedAt(Instant.now());
        return roundRepository.save(round);
    }

    private RoundWork loadWork(BlackjackRound round) {
        List<HandState> hands = fromJson(round.getPlayerHands(), HANDS_TYPE);
        DealerState dealerState = fromJson(round.getDealerHand(), DealerState.class);
        ShoeState shoeState = fromJson(round.getShoe(), ShoeState.class);
        RoundContext ctx = fromJson(round.getOutcomes(), RoundContext.class);
        return new RoundWork(hands, dealerState, shoeState.toShoe(), ctx);
    }

    private BlackjackRoundResponse respond(BlackjackRound round, GameSession session, BlackjackMathDefinition math) {
        BigDecimal balance = walletGateway.balance(session.getPlayerId()).balance();
        return buildResponse(round, loadWork(round), session, math, balance);
    }

    private BlackjackRoundResponse buildResponse(BlackjackRound round, RoundWork work, GameSession session,
                                                 BlackjackMathDefinition math, BigDecimal balance) {
        List<HandState> hands = work.hands();
        RoundContext ctx = work.context();
        boolean settled = round.getStatus() == RoundStatus.SETTLED;

        List<BlackjackRoundResponse.HandView> handViews = hands.stream().map(this::toHandView).toList();
        BlackjackRoundResponse.DealerView dealerView = toDealerView(work.dealer(), settled);

        List<BlackjackAction> available = availableActions(round, hands, ctx, math, balance, settled);

        BlackjackRoundResponse.InsuranceView insuranceView = null;
        if (ctx.getInsurance() != null) {
            InsuranceState ins = ctx.getInsurance();
            insuranceView = BlackjackRoundResponse.InsuranceView.builder()
                    .bet(ins.getBet()).resolved(ins.isResolved()).won(ins.isWon()).payout(ins.getPayout())
                    .build();
        }

        return BlackjackRoundResponse.builder()
                .sessionId(session.getSessionId())
                .sessionVersion(session.getSessionVersion())
                .roundId(round.getRoundId())
                .mathVersion(round.getMathVersion())
                .status(round.getStatus().name())
                .playerHands(handViews)
                .activeHandIndex(ctx.getActiveHandIndex())
                .dealer(dealerView)
                .availableActions(available)
                .totalBet(round.getTotalBet())
                .totalWin(round.getTotalWin())
                .balance(balance)
                .insurance(insuranceView)
                .build();
    }

    private List<BlackjackAction> availableActions(BlackjackRound round, List<HandState> hands, RoundContext ctx,
                                                   BlackjackMathDefinition math, BigDecimal balance,
                                                   boolean settled) {
        if (settled) {
            return DEAL_ONLY;
        }
        int idx = ctx.getActiveHandIndex();
        if (idx < 0 || idx >= hands.size()) {
            return List.of();
        }
        HandState active = hands.get(idx);
        List<BlackjackAction> structural = actions.legalActions(
                active.cardList(), active.isFromSplit(), hands.size(), ctx.isInsuranceOffered(), math);
        // Strip DOUBLE/SPLIT if the player cannot fund the extra (one more base bet).
        boolean canAffordExtra = balance.compareTo(round.getBet()) >= 0;
        if (canAffordExtra) {
            return structural;
        }
        return structural.stream()
                .filter(a -> a != BlackjackAction.DOUBLE && a != BlackjackAction.SPLIT)
                .toList();
    }

    private BlackjackRoundResponse.HandView toHandView(HandState hand) {
        HandValue value = hand.handValue();
        return BlackjackRoundResponse.HandView.builder()
                .cards(hand.cardList().stream().map(this::toCardView).toList())
                .value(value.total())
                .soft(value.isSoft())
                .status(hand.getStatus().name())
                .bet(hand.getBet())
                .outcome(hand.getOutcome() != null ? hand.getOutcome().name() : null)
                .payout(hand.getPayout())
                .build();
    }

    private BlackjackRoundResponse.DealerView toDealerView(DealerState dealerState, boolean revealed) {
        if (revealed) {
            return BlackjackRoundResponse.DealerView.builder()
                    .cards(dealerState.cardList().stream().map(this::toCardView).toList())
                    .value(dealerState.handValue().total())
                    .hidden(false)
                    .build();
        }
        // In progress — expose ONLY the upcard; the hole card never leaves the server.
        return BlackjackRoundResponse.DealerView.builder()
                .cards(List.of(toCardView(dealerState.upcard())))
                .value(null)
                .hidden(true)
                .build();
    }

    private BlackjackRoundResponse.CardView toCardView(Card card) {
        return BlackjackRoundResponse.CardView.builder()
                .rank(card.rank().code())
                .suit(card.suit().name())
                .suitSymbol(card.suit().symbol())
                .color(card.color().name())
                .code(card.code())
                .build();
    }

    // ===================================================================== shared infra (mirrors roulette)

    private GameSession createSession(String gameId, String playerId, String currency,
                                      WalletAuthenticateResponse auth) {
        if (auth.currency() != null && !auth.currency().equals(currency)) {
            throw new RgsException(ErrorCode.CURRENCY_MISMATCH,
                    "Wallet currency " + auth.currency() + " does not match JWT currency " + currency);
        }
        BlackjackMathDefinition math = catalog.requireByGameId(gameId).math();
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
                .nextActionAllowed(BlackjackAction.DEAL.name())
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
                    "Session " + sessionId + " is not a blackjack game: " + gameId);
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
        log.debug("blackjack debit ok player={} txId={} amount={}", playerId, txId, amount.amount());
    }

    private void executeCredit(String playerId, GameSession session, String roundId, String txId, Money amount) {
        WalletCreditRequest req = new WalletCreditRequest(playerId, session.getSessionId(), roundId, txId,
                amount.amount(), session.getCurrency(), WalletTransactionType.WIN);
        try {
            walletGateway.credit(req, txId, session.getCurrency());
            log.debug("blackjack credit ok player={} txId={} amount={}", playerId, txId, amount.amount());
        } catch (RuntimeException ex) {
            log.error("blackjack credit failed — rolling back the round's debits player={} roundId={} cause={}",
                    playerId, roundId, ex.getMessage());
            String rollbackTxId = txId + ":rollback";
            try {
                walletGateway.rollback(new WalletRollbackRequest(playerId, roundId, rollbackTxId,
                        RollbackReason.TECHNICAL_ERROR), rollbackTxId, session.getCurrency());
            } catch (RuntimeException rollbackEx) {
                log.error("blackjack rollback also failed player={} roundId={}", playerId, roundId, rollbackEx);
            }
            throw ex;
        }
    }

    private BlackjackAction parseAction(String action) {
        try {
            return BlackjackAction.valueOf(action);
        } catch (IllegalArgumentException ex) {
            throw new RgsException(ErrorCode.VALIDATION_ERROR, "Unknown action: " + action);
        }
    }

    private BigDecimal normalize(BigDecimal amount, String currency) {
        return amount.setScale(Money.minorUnitScale(currency), RoundingMode.HALF_UP);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "Cannot serialize JSON: " + ex.getMessage(), ex);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "Cannot deserialize JSON: " + ex.getMessage(), ex);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "Cannot deserialize JSON: " + ex.getMessage(), ex);
        }
    }

    /** In-memory bundle of a round's deserialized working state. */
    private record RoundWork(List<HandState> hands, DealerState dealer, Shoe shoe, RoundContext context) {
    }
}
