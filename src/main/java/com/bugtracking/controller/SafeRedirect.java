package com.bugtracking.controller;

/**
 * Where a form goes back to when it was posted from somewhere other than the
 * page that owns it.
 *
 * <p>The team and project forms live on Settings and redirect there. The board
 * now carries the same forms in a drawer, and landing on Settings after ticking
 * somebody onto a project from the board is losing your place. So those posts
 * may carry a {@code back} — the board they were opened from, filters and all.
 *
 * <p>It is a value from a request, so it is treated as one: a path on this app
 * and nothing else. Without that check, {@code back=//example.com} is an open
 * redirect — a link that signs somebody in here and then hands them to a page
 * of somebody else's choosing.
 */
final class SafeRedirect {

    private SafeRedirect() {
    }

    /**
     * {@code "redirect:" + back} when {@code back} is a path on this app,
     * otherwise the caller's own destination.
     */
    static String to(String back, String fallback) {
        if (back == null) {
            return fallback;
        }
        String path = back.trim();
        if (path.isEmpty() || path.charAt(0) != '/') {
            return fallback;                       // absolute, or a scheme
        }
        // "//host" and "/\host" are both read as protocol-relative by browsers.
        if (path.length() > 1 && (path.charAt(1) == '/' || path.charAt(1) == '\\')) {
            return fallback;
        }
        // A newline in a redirect is a header split; nothing legitimate has one.
        if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            return fallback;
        }
        return "redirect:" + path;
    }
}
