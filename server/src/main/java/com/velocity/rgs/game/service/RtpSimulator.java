package com.velocity.rgs.game.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * CLI runner that invokes {@link RtpSimulationService} once on startup under the {@code simulator}
 * profile (M5 Task 5.9 / M7 Task 7.6). Spin counts and bet size are taken from
 * {@code rgs.simulator.*} properties.
 */
@Slf4j
@Component
@Profile("simulator")
@RequiredArgsConstructor
public class RtpSimulator implements CommandLineRunner {

    private final RtpSimulationService simulationService;

    @Value("${rgs.simulator.spins:100000}")
    private long spins;

    @Value("${rgs.simulator.bonusBuyFreeSpins:0}")
    private long bonusBuyFreeSpins;

    @Value("${rgs.simulator.bonusBuyPickCollect:0}")
    private long bonusBuyPickCollect;

    @Value("${rgs.simulator.gameId:aztec-fire}")
    private String gameId;

    @Value("${rgs.simulator.mathVersion:v1}")
    private String mathVersion;

    @Value("${rgs.simulator.bet:1.00}")
    private BigDecimal bet;

    @Override
    public void run(String... args) {
        long buyFs = bonusBuyFreeSpins == 0 ? Math.max(1, spins / 50) : bonusBuyFreeSpins;
        long buyPc = bonusBuyPickCollect == 0 ? Math.max(1, spins / 50) : bonusBuyPickCollect;
        RtpSimulationRequest req = RtpSimulationRequest.builder()
                .gameId(gameId)
                .mathVersion(mathVersion)
                .bet(bet)
                .spinsBaseGame(spins)
                .spinsBonusBuyFreeSpins(buyFs)
                .spinsBonusBuyPickCollect(buyPc)
                .pickStrategy(RtpSimulationRequest.PickStrategy.RANDOM_UNOPENED)
                .build();
        RtpReport report = simulationService.run(req, "cli-" + UUID.randomUUID());
        log.info("RTP simulator CLI summary: {}", report);
    }
}
