package com.velocity.rgs.common.idempotency;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only endpoint used to validate the {@link IdempotencyAspect} end-to-end.
 * Active only under the 'test' profile.
 */
@Profile("test")
@RestController
@RequestMapping("/api/test/idempotency")
@RequiredArgsConstructor
public class TestIdempotentController {

    private final AtomicInteger invocations = new AtomicInteger();

    public int invocationCount() {
        return invocations.get();
    }

    public void reset() {
        invocations.set(0);
    }

    @PostMapping
    @Idempotent(scope = "test:echo:{playerId}", ttlHours = 1)
    public ResponseEntity<EchoResponse> echo(@RequestBody EchoRequest request) {
        int n = invocations.incrementAndGet();
        return ResponseEntity.ok(new EchoResponse(request.message(), n, UUID.randomUUID().toString()));
    }

    public record EchoRequest(@NotBlank String message) {}
    public record EchoResponse(String echoed, int invocation, String serverGenerated) {}
}
