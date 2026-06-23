package com.velocity.rgs.slot.math.domain;

import java.util.Objects;

/**
 * Game symbol descriptor. {@code substitutes} is meaningful only when {@code type} is {@link SymbolType#WILD}
 * and declares which {@link SymbolType} the wild can substitute for. Per A.4, wilds substitute STANDARD only.
 */
public record Symbol(int id, String name, SymbolType type, SymbolType substitutes) {

    public Symbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (type != SymbolType.WILD && substitutes != null) {
            throw new IllegalArgumentException("substitutes only allowed on WILD symbols");
        }
    }

    public boolean isWild() {
        return type == SymbolType.WILD;
    }

    public boolean isScatter() {
        return type == SymbolType.SCATTER;
    }

    public boolean substitutesFor(SymbolType target) {
        return isWild() && substitutes == target;
    }
}
