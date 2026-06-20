package com.velocity.rgs.wallet.service;

import com.velocity.rgs.common.money.Money;
import com.velocity.rgs.wallet.config.WalletProperties;
import com.velocity.rgs.wallet.domain.WalletBalance;
import com.velocity.rgs.wallet.persistence.WalletBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Active when {@code rgs.mode=demo} (the default). On the first {@code authenticate}
 * call for an unknown player, seeds a {@link WalletBalance} row with the configured
 * starting balance so that demo gameplay can proceed without an external wallet.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rgs", name = "mode", havingValue = "demo", matchIfMissing = true)
@RequiredArgsConstructor
public class WalletDemoSeeder {

    private final WalletBalanceRepository balanceRepository;
    private final WalletProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletBalance seedIfAbsent(String playerId, String requestedCurrency) {
        return balanceRepository.findById(playerId).orElseGet(() -> {
            String currency = requestedCurrency != null && Money.isSupported(requestedCurrency)
                    ? requestedCurrency
                    : properties.getDemo().getCurrency();
            Money seed = Money.fromMinor(properties.getDemo().getStartingBalanceMinor(), currency);
            WalletBalance row = WalletBalance.builder()
                    .playerId(playerId)
                    .currency(currency)
                    .balance(seed.amount().setScale(4, Money.ROUNDING))
                    .balanceMinor(seed.toMinor())
                    .version(0)
                    .updatedAt(Instant.now())
                    .build();
            WalletBalance saved = balanceRepository.save(row);
            log.info("Seeded demo wallet for player={} balance={} {}", playerId, saved.getBalance(), currency);
            return saved;
        });
    }

    public BigDecimal startingAmount(String currency) {
        return Money.fromMinor(properties.getDemo().getStartingBalanceMinor(), currency).amount();
    }
}
