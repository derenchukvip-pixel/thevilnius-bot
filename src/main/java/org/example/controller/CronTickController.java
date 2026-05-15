package org.example.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.scheduler.PostScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight endpoint called by an external cron service (e.g. cron-job.org) every 30 minutes.
 * Serves two purposes:
 *  1. Keeps the Render free-tier service awake (prevents spin-down).
 *  2. Triggers the publication check — same logic as the internal @Scheduled cron.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CronTickController {

    private final PostScheduler postScheduler;

    @GetMapping("/cron/tick")
    public String tick() {
        log.info("External cron tick received — triggering publication check in background");
        Thread thread = new Thread(() -> {
            try {
                postScheduler.run();
            } catch (Exception e) {
                log.error("Background publication check failed", e);
            }
        }, "cron-tick-publisher");
        thread.setDaemon(false);
        thread.start();
        return "ok";
    }
}


