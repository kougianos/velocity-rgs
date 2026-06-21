package com.velocity.rgs.wallet.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ApiError;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.Optional;

/**
 * Spring configuration that exposes a tuned {@link WebClient} bean for the
 * {@link OperatorWalletGateway} (M6 Task 6.4). Only loaded when {@code rgs.wallet.mode=operator}
 * so the {@code spring-boot-starter-webflux} dependency stays dormant for the rest of the
 * application.
 */
@Configuration
@ConditionalOnProperty(prefix = "rgs.wallet", name = "mode", havingValue = "operator")
@EnableConfigurationProperties(OperatorWalletProperties.class)
public class OperatorWalletConfiguration {

    @Bean
    public WebClient operatorWalletWebClient(OperatorWalletProperties properties, ObjectMapper objectMapper) {
        int timeoutMs = (int) properties.getTimeoutMs();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(timeoutMs / 1000 + 1))
                        .addHandlerLast(new WriteTimeoutHandler(timeoutMs / 1000 + 1)));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(errorTranslatingFilter(objectMapper));
        Optional.ofNullable(properties.getAuthToken())
                .filter(token -> !token.isBlank())
                .ifPresent(token -> builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        return builder.build();
    }

    /**
     * Translates 4xx/5xx wallet responses into {@link RgsException}s carrying the canonical error code
     * supplied by the upstream wallet (when it follows our {@link ApiError} shape) so the rest of the
     * engine treats operator failures identically to in-process failures.
     */
    static ExchangeFilterFunction errorTranslatingFilter(ObjectMapper objectMapper) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (!response.statusCode().isError()) {
                return Mono.just(response);
            }
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(translate(response.statusCode().value(), body, objectMapper)));
        });
    }

    private static RgsException translate(int httpStatus, String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            return new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Operator wallet returned HTTP " + httpStatus + " with no body");
        }
        try {
            ApiError parsed = objectMapper.readValue(body, ApiError.class);
            ErrorCode code = resolveCode(parsed.code(), httpStatus);
            return new RgsException(code, parsed.message() != null ? parsed.message()
                    : "Operator wallet error", null);
        } catch (Exception ignored) {
            return new RgsException(resolveCode(null, httpStatus),
                    "Operator wallet returned HTTP " + httpStatus + ": " + body);
        }
    }

    private static ErrorCode resolveCode(String code, int httpStatus) {
        if (code != null) {
            try {
                return ErrorCode.valueOf(code);
            } catch (IllegalArgumentException ignored) {
                // fall through to status-based mapping
            }
        }
        if (httpStatus == 401) return ErrorCode.AUTH_FAILED;
        if (httpStatus == 403) return ErrorCode.FORBIDDEN_ACTION;
        if (httpStatus == 404) return ErrorCode.ORIGINAL_TRANSACTION_NOT_FOUND;
        if (httpStatus == 409) return ErrorCode.DUPLICATE_TRANSACTION;
        if (httpStatus >= 400 && httpStatus < 500) return ErrorCode.VALIDATION_ERROR;
        return ErrorCode.INTERNAL_ERROR;
    }
}
