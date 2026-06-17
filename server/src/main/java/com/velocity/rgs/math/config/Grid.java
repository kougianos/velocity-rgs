package com.velocity.rgs.math.config;

public record Grid(int rows, int cols) {

    public Grid {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows and cols must be positive");
        }
    }
}
