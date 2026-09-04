package com.bugtracking.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How often one client may write, so a portal account cannot bury a board.
 *
 * <p>In memory, and deliberately. This is one instance of a small tracker, the
 * limits are generous enough that nobody working normally will ever meet one,
 * and the worst a restart costs is that somebody's allowance starts again — a
 * table and a migration to hold that would be machinery guarding nothing.
 *
 * <p>It is not spam protection either, because the portal has no anonymous door
 * to spam through: everything here is already signed in as a named client on a
 * named project, and anybody abusing it can simply be switched off. What it
 * stops is the accident — a stuck form, a double-tapped button, a script
 * somebody pointed at the wrong URL — turning into three hundred cards.
 */
@Component
public class GuestRateLimit {

    /** Reports one client may raise in an hour. */
    private static final int REPORTS_PER_HOUR = 12;

    /** Replies, which are cheaper and more conversational, so a higher ceiling. */
    private static final int REPLIES_PER_HOUR = 40;

    private static final Duration WINDOW = Duration.ofHours(1);

    /** Thrown when somebody has to wait. The message is what they are shown. */
    public static class TooOftenException extends RuntimeException {
        public TooOftenException(String message) {
            super(message);
        }
    }

    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    void checkReport(Long guestId) {
        check("report:" + guestId, REPORTS_PER_HOUR,
                "That is a lot of reports in one hour. Give it a few minutes,"
                        + " and add anything else to the report you just sent.");
    }

    void checkReply(Long guestId) {
        check("reply:" + guestId, REPLIES_PER_HOUR,
                "That is a lot of replies in one hour. Give it a few minutes.");
    }

    /**
     * A sliding window rather than a counter reset on the hour: a fixed bucket
     * lets somebody spend a whole allowance at 10:59 and a second one at 11:00,
     * which is the burst this exists to stop.
     */
    private void check(String key, int allowed, String message) {
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW.toMillis();
        Deque<Long> stamps = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
                stamps.removeFirst();
            }
            if (stamps.size() >= allowed) {
                throw new TooOftenException(message);
            }
            stamps.addLast(now);
        }
    }
}
