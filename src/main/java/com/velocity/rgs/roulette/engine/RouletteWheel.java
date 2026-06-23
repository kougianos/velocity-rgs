package com.velocity.rgs.roulette.engine;

import com.velocity.rgs.roulette.config.RouletteMathDefinition;
import com.velocity.rgs.roulette.domain.PocketColor;
import com.velocity.rgs.rng.RandomNumberGenerator;
import org.springframework.stereotype.Component;

/**
 * Draws the winning pocket for a spin. One draw — {@code rng.nextIndex(pocketCount)} → a number in
 * {@code [0, pocketCount)} — taken from the round's RNG so it is captured in the draw log and replays
 * deterministically (mirrors how the slot grid is generated). The colour is resolved from the configured
 * red set ({@code 0} is always green).
 */
@Component
public class RouletteWheel {

    public RouletteSpin spin(RouletteMathDefinition math, RandomNumberGenerator rng) {
        int number = rng.nextIndex(math.pocketCount());
        return new RouletteSpin(number, colorOf(number, math));
    }

    public static PocketColor colorOf(int number, RouletteMathDefinition math) {
        if (number == 0) {
            return PocketColor.GREEN;
        }
        return math.redNumbers().contains(number) ? PocketColor.RED : PocketColor.BLACK;
    }
}
