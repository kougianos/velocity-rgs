package com.velocity.rgs.jackpot.api;

import com.velocity.rgs.jackpot.JackpotService;
import com.velocity.rgs.jackpot.domain.JackpotPoolView;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The progressive pools, read-only (§2).
 *
 * <p>Anonymous, like the game catalog and for the same reason: the lobby renders the strip before
 * anyone has authenticated, and a pool value is a public number by nature - it is advertised on the
 * page precisely so people who are not logged in can see it. The response carries no player or session
 * id, so there is nothing here to scope to a caller.
 *
 * <p>Currency is a parameter rather than read from a token, since there is no token. It defaults to EUR
 * because that is what the demo wallet is denominated in; a currency with no pools returns an empty
 * list rather than an error, which is the truthful answer to "what jackpots do you run in this
 * currency" for one that has none.
 */
@RestController
@RequestMapping("/api/v1/jackpots")
@RequiredArgsConstructor
@Validated
public class JackpotController {

    private final JackpotService jackpotService;

    @GetMapping
    public ResponseEntity<List<JackpotPoolView>> pools(
            @RequestParam(defaultValue = "EUR")
            @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
            String currency) {
        return ResponseEntity.ok(jackpotService.pools(currency));
    }
}
