package com.velocity.rgs.audit.replay;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Gives a shared round a URL a person can look at: {@code /r/<token>} forwards to the static replay page,
 * which reads the token back off its own location and calls {@link PublicReplayController}.
 *
 * <p>The forward keeps the token in the address bar rather than redirecting to
 * {@code /replay.html?t=…}, so what gets pasted into a CV or an email is what stays on screen. No
 * verification happens here - an invalid token has to reach the page for the page to be able to explain
 * itself, and answering 400 from this forward would replace that explanation with a browser error.
 */
@Controller
public class PublicReplayViewController {

    @GetMapping("/r/{token}")
    public String view(@PathVariable String token) {
        return "forward:/replay.html";
    }
}
