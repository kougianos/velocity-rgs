package com.velocity.rgs.rng;

/**
 * Round-scoped RNG abstraction (Milestone 2). The grid generator and any feature engine that needs
 * randomness must depend on this interface so production code can use {@link SecureRandomNumberGenerator}
 * while tests and the replay service use {@link DeterministicReplayRng}.
 */
public interface RandomNumberGenerator {

    /**
     * Returns a non-negative integer in {@code [0, boundExclusive)}.
     *
     * @throws IllegalArgumentException if {@code boundExclusive <= 0}
     */
    int nextIndex(int boundExclusive);
}
