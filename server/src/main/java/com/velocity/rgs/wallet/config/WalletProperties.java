package com.velocity.rgs.wallet.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "rgs.wallet")
public class WalletProperties {

    /** Active wallet backend: {@code internal} (POC) or {@code operator} (external). */
    private String mode = "internal";

    private Demo demo = new Demo();

    private Operator operator = new Operator();

    @Getter
    @Setter
    public static class Demo {
        /** Starting balance in currency minor units for a freshly-seeded player. */
        private long startingBalanceMinor = 1_000_000L;
        /** Fallback currency when the JWT does not carry the {@code cur} claim. */
        private String currency = "EUR";
    }

    @Getter
    @Setter
    public static class Operator {
        private String baseUrl;
        private int timeoutMs = 2_000;
    }
}
