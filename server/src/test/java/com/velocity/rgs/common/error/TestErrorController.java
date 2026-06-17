package com.velocity.rgs.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("test")
@RestController
@RequestMapping("/api/test/errors")
public class TestErrorController {

    @PostMapping("/throw")
    public String throwError(@RequestParam ErrorCode code) {
        throw new RgsException(code, "boom: " + code);
    }

    @PostMapping("/optimistic")
    public String optimistic() {
        throw new OptimisticLockingFailureException("stale");
    }

    @PostMapping("/unhandled")
    public String unhandled() {
        throw new IllegalStateException("internal");
    }

    @PostMapping("/validate")
    public String validate(@Valid @RequestBody Payload payload) {
        return "ok";
    }

    public record Payload(@NotBlank String name, @Min(1) int amount) {}
}
