package com.solace.sm.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Local-only Cache demo entrypoint, authorised by the temporary 2026-09-14 demo waivers.
 * It is deliberately separate from console-gateway and is not production control-plane wiring.
 */
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
