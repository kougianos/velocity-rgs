package com.velocity.rgs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VelocityRgsApplication {

    public static void main(String[] args) {
        SpringApplication.run(VelocityRgsApplication.class, args);
    }
}
