package com.bugtracking.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Where every failure that never reached {@link GlobalExceptionHandler} lands:
 * an address with no controller behind it, a 403, an exception raised in a
 * filter before MVC ran, and any exception that handler does not name. Spring
 * Boot answers all of those with its whitelabel page — the grey paragraph that
 * tells a person nothing they can act on and tells us nothing we can grep for.
 *
 * <p>So this replaces it: the status said in words, one sentence of what to do
 * about it, and a reference that is printed on the page <em>and</em> logged, so
 * "it broke" becomes "it broke, here is the line".
 *
 * <p>Declaring any {@link ErrorController} bean is what makes Boot stand its own
 * down, so there is no configuration to keep in step with this class.
 *
 * <p>Nothing below reads anything but the request — no database, no principal,
 * no session — and {@code error.html} sits outside the app's layout for the same
 * reason. An error page that needs the app to be working is not an error page.
 * {@link GlobalModelAttributes} would have undone that on its own, since it runs
 * four queries for the navbar before any {@code @Controller} handler is reached;
 * it now stands down on an error dispatch, which is what keeps this page cheap
 * and, more to the point, what keeps a database outage from taking down the page
 * that reports it.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ErrorPageController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ErrorPageController.class);

    // Locale.ENGLISH so the stamp on the page reads the same whatever locale the
    // machine is set to - it is quoted alongside a log line, and those are English.
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.ENGLISH);

    /** How far down a cause chain the details block reads, and how much of each message. */
    private static final int CAUSE_DEPTH = 4;
    private static final int MESSAGE_CAP = 400;

    /** Set in application.properties. Off unless asked for: a stack trace is ours, not the caller's. */
    private final boolean showDetails;

    public ErrorPageController(@Value("${bugtracking.error.show-details:false}") boolean showDetails) {
        this.showDetails = showDetails;
    }

    /**
     * A browser asked, so it gets the page. Split from the JSON below the same
     * way Spring's own error controller splits it — by what the caller accepts,
     * because the API answering a script with an HTML page is the same defect in
     * a different costume.
     */
    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String page(HttpServletRequest request, HttpServletResponse response, Model model) {
        Failure failure = failure(request);
        response.setStatus(failure.status());

        model.addAttribute("status", failure.status());
        model.addAttribute("reason", failure.reason());
        model.addAttribute("kind", failure.kind());
        model.addAttribute("icon", failure.icon());
        model.addAttribute("headline", failure.headline());
        model.addAttribute("advice", failure.advice());
        model.addAttribute("path", failure.path());
        model.addAttribute("retry", failure.retry());
        model.addAttribute("reference", failure.reference());
        model.addAttribute("detail", failure.detail());
        model.addAttribute("signIn", failure.status() == 401 || failure.status() == 403);
        model.addAttribute("when", WHEN.format(LocalDateTime.now()));
        return "error";
    }

    /** The same failure for anything that is not a browser — the JSON API, above all. */
    @RequestMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> json(HttpServletRequest request) {
        Failure failure = failure(request);

        // "error" holds the sentence a person reads, which is the shape every
        // other JSON error in this app already has.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", failure.headline());
        body.put("status", failure.status());
        if (failure.path() != null) {
            body.put("path", failure.path());
        }
        body.put("reference", failure.reference());
        if (failure.detail() != null) {
            body.put("detail", failure.detail());
        }
        return ResponseEntity.status(failure.status()).body(body);
    }

    /** Everything the page and the JSON body both need, worked out once. */
    private record Failure(int status, String reason, String kind, String icon,
                           String headline, String advice,
                           String path, String retry, String reference, String detail) {
    }

    private Failure failure(HttpServletRequest request) {
        int status = status(request);
        Throwable error = unwrap((Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        String path = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String reference = reference();
        Copy copy = copyFor(status);

        // One line, not a second stack trace: the dispatcher has already logged
        // the throwable in full. This is the line that ties that to the page
        // somebody is looking at, so it carries the reference and little else.
        if (status >= 500) {
            log.error("[{}] {} on {}{}", reference, status, path,
                    error == null ? "" : " — " + summarise(error));
        } else {
            log.debug("[{}] {} on {}", reference, status, path);
        }

        HttpStatus resolved = HttpStatus.resolve(status);
        return new Failure(
                status,
                resolved == null ? "Error" : resolved.getReasonPhrase(),
                status >= 500 ? "server" : "client",
                copy.icon(),
                copy.headline(),
                copy.advice(),
                path,
                retry(request, status, path),
                reference,
                detail(status, error));
    }

    private static int status(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        // No attribute means nothing forwarded here — somebody typed /error into
        // the address bar. Treated as a 500, the way Spring's own page treats it.
        return code instanceof Integer value && value >= 100 ? value : 500;
    }

    /**
     * The address to offer a second go at, or null where a second go is pointless.
     *
     * <p>Only ever a path this app served. A request for {@code //evil.example}
     * arrives here as its own URI, and a bare {@code href} to that is a
     * protocol-relative link — a reload button that walks the reader off the
     * site. Same for a backslash, which browsers straighten into a slash.
     */
    private static String retry(HttpServletRequest request, int status, String path) {
        boolean worthRetrying = status >= 500 || status == 408 || status == 429;
        if (!worthRetrying || path == null || path.isBlank()) {
            return null;
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) {
            return null;
        }
        String query = request.getQueryString();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    /**
     * The cause chain, when the app has been told it may show it. Only for a
     * 5xx: a 404 has no exception worth reading, and this is the one place the
     * app would say more than it should to whoever is looking.
     */
    private String detail(int status, Throwable error) {
        if (!showDetails || status < 500 || error == null) {
            return null;
        }
        StringBuilder chain = new StringBuilder();
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < CAUSE_DEPTH; cause = cause.getCause(), depth++) {
            if (!chain.isEmpty()) {
                chain.append("\ncaused by ");
            }
            chain.append(summarise(cause));
        }
        return chain.toString();
    }

    /**
     * Past the wrappers, to the first exception that says something of its own.
     *
     * <p>What arrives here is usually a {@code ServletException} whose entire
     * message is {@code "Request processing failed: "} followed by the next
     * exception's {@code toString()} — so an unedited chain opens by saying
     * nothing, twice, and the line that matters is pushed off the first screen.
     * A link that contains the whole of the one below it is exactly that and is
     * skipped; one that adds wording of its own — "JDBC exception executing SQL
     * [...]", which is how you learn <em>which</em> query broke — is kept.
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current != null) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            String said = current.getMessage();
            if (said == null || !said.contains(cause.toString())) {
                return current;
            }
            current = cause;
        }
        return null;
    }

    /** One throwable as a line: its type, and what it said, cut to a length a page can hold. */
    private static String summarise(Throwable error) {
        String said = error.getMessage();
        if (said == null || said.isBlank()) {
            return error.getClass().getName();
        }
        String trimmed = said.length() > MESSAGE_CAP ? said.substring(0, MESSAGE_CAP) + "…" : said;
        return error.getClass().getName() + ": " + trimmed;
    }

    /** Short enough to be read down a phone, unique enough to grep the log for. */
    private static String reference() {
        return "ERR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** The heading, the sentence under it, and which of the three icons it takes. */
    private record Copy(String headline, String advice, String icon) {
    }

    private static final String ALERT = "i-alert";

    /**
     * What each failure is called in words a person can act on.
     *
     * <p>Every line says whose problem it is and what to do next, because those
     * are the only two things the reader wants and neither is a status code.
     */
    private static Copy copyFor(int status) {
        return switch (status) {
            case 400 -> new Copy("That request did not make sense",
                    "Something in the address or the form was not in a shape the app could read. "
                            + "Go back, check what you sent, and try it again.",
                    ALERT);
            case 401 -> new Copy("You are not signed in",
                    "Your session has most likely expired. Sign in again and you will land back on the board.",
                    "i-lock");
            case 403 -> new Copy("That is not yours to open",
                    "You are signed in, but this is not something your account can see. "
                            + "Ask whoever owns it to add you.",
                    "i-lock");
            case 404 -> new Copy("There is nothing at that address",
                    "The link may be older than what it points at, or the thing itself was deleted. "
                            + "The board is the quickest way back to solid ground.",
                    "i-search");
            case 405 -> new Copy("That address does not accept that",
                    "The page asked this address for something it does not do. That is a broken link "
                            + "or a broken form, not anything you typed.",
                    ALERT);
            case 409 -> new Copy("That clashes with something already saved",
                    "Nothing was changed. Reload to see what is there now, then try again.",
                    ALERT);
            case 413 -> new Copy("That upload was too large",
                    "Nothing on the form was saved. Attach fewer files, or smaller ones, and send it again.",
                    ALERT);
            case 429 -> new Copy("Too many requests, too quickly",
                    "Give it a few seconds and try again.",
                    "i-clock");
            case 503 -> new Copy("The app is not ready yet",
                    "Something it depends on is still starting up, or cannot be reached. "
                            + "This usually clears on its own — try again in a moment.",
                    "i-clock");
            default -> status >= 500
                    ? new Copy("Something went wrong on our side",
                            "You did not cause this, and nothing you were working on was saved. Try again — "
                                    + "and if it keeps happening, quote the reference below: it is in the "
                                    + "server log beside what actually failed.",
                            ALERT)
                    : new Copy("That request could not be carried out",
                            "The app understood the address but would not act on it. "
                                    + "Going back to the board and starting again is usually enough.",
                            ALERT);
        };
    }
}
