package com.velocity.rgs.wallet.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@link OperatorWalletGateway} active under the {@code wallet-operator}
 * profile (M6 Task 6.4). Bound from {@code rgs.wallet.operator.*}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rgs.wallet.operator")
public class OperatorWalletProperties {

    /** Base URL of the external operator wallet API (e.g. {@code https://wallet.acme.tld}). */
    private String baseUrl = "http://localhost:9090";

    /** Connect + read timeout for a single wallet call. */
    private long timeoutMs = 2000;

    /** Optional static bearer token forwarded as {@code Authorization} on every wallet call. */
    private String authToken;
}
