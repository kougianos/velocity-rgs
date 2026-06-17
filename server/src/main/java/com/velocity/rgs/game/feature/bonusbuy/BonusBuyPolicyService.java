package com.velocity.rgs.game.feature.bonusbuy;

import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.math.config.BonusBuyOption;
import com.velocity.rgs.math.config.SlotMathDefinition;
import com.velocity.rgs.math.domain.BonusBuyType;
import com.velocity.rgs.session.domain.GameSession;
import com.velocity.rgs.session.domain.GameState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Validates Bonus Buy eligibility per Section 4 / M5 Task 5.6:
 * <ul>
 *   <li>global {@code bonusBuyEnabled} flag (server-side override)</li>
 *   <li>jurisdiction allowlist (optional)</li>
 *   <li>game-level math availability ({@code bonusBuyOptions})</li>
 *   <li>session state legality (must be {@code BASE_GAME})</li>
 *   <li>affordability against current wallet balance</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BonusBuyPolicyService {

    private final BonusBuyPolicyProperties properties;

    public BonusBuyOption requireOption(SlotMathDefinition math, BonusBuyType type,
                                        GameSession session, BigDecimal balance,
                                        BigDecimal betSize, String jurisdiction) {
        if (!properties.isEnabled()) {
            throw new RgsException(ErrorCode.BONUS_BUY_DISABLED,
                    "Bonus Buy is disabled by server policy");
        }
        if (!properties.getAllowedJurisdictions().isEmpty()
                && jurisdiction != null
                && !properties.getAllowedJurisdictions().contains(jurisdiction)) {
            throw new RgsException(ErrorCode.BONUS_BUY_DISABLED,
                    "Bonus Buy is not permitted in jurisdiction: " + jurisdiction);
        }
        if (session.getCurrentState() != GameState.BASE_GAME) {
            throw new RgsException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Bonus Buy is only allowed from BASE_GAME");
        }

        Optional<BonusBuyOption> match = math.bonusBuyOptions().stream()
                .filter(o -> o.buyType() == type)
                .findFirst();
        BonusBuyOption option = match.orElseThrow(() -> new RgsException(ErrorCode.BONUS_BUY_DISABLED,
                "Bonus Buy type not offered for game: " + type));

        BigDecimal cost = betSize.multiply(option.costMultiplier());
        if (balance != null && balance.compareTo(cost) < 0) {
            throw new RgsException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Insufficient funds for Bonus Buy: cost=" + cost + " balance=" + balance);
        }
        if (properties.getMinimumBalance() != null
                && balance != null
                && balance.compareTo(properties.getMinimumBalance()) < 0) {
            throw new RgsException(ErrorCode.BONUS_BUY_DISABLED,
                    "Player balance below Bonus Buy floor: " + properties.getMinimumBalance());
        }
        return option;
    }
}
