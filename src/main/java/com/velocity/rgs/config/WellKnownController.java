package com.velocity.rgs.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WellKnownController {

    @GetMapping("/.well-known/appspecific/com.chrome.devtools.json")
    public ResponseEntity<Void> chromeDevtoolsProbe() {
        return ResponseEntity.noContent().build();
    }
}
