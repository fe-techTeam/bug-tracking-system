package com.bugtracking.controller;

import com.bugtracking.config.AttachmentProperties;
import com.bugtracking.service.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Turns the handful of failures a caller can actually do something about into a
 * page for the UI and a JSON body for the API — each with the status code that
 * says what happened, because a "not found" answered with 200 is a lie to the
 * browser, the cache and the crawler alike.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final GlobalModelAttributes globals;
    private final AttachmentProperties attachments;

    public GlobalExceptionHandler(GlobalModelAttributes globals, AttachmentProperties attachments) {
        this.globals = globals;
        this.attachments = attachments;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Object handleNotFound(NoSuchElementException ex, HttpServletRequest request, Model model) {
        String message = said(ex, "That does not exist, or it was deleted for good.");
        if (isApi(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", message));
        }
        return page(model, request, "Bug not found", message);
    }

    /** A file the caller could fix by choosing a different one. */
    @ExceptionHandler(AttachmentService.RejectedFileException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleRejectedFile(AttachmentService.RejectedFileException ex,
                                     HttpServletRequest request, Model model) {
        if (isApi(request)) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        return page(model, request, "That file was not accepted", ex.getMessage());
    }

    /**
     * A path or query value the app cannot even parse: /bugs/abc, or a status
     * a bookmark still spells the way it was spelled before the rename. The
     * caller's to fix, so it is a 400 and not the whitelabel 500 it was.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                     HttpServletRequest request, Model model) {
        String message = "\"" + ex.getValue() + "\" is not a valid " + ex.getName() + ".";
        if (isApi(request)) {
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
        return page(model, request, "That link does not add up", message);
    }

    /**
     * A constraint the entity declares but no controller checked — a comment
     * past the 2000-character column, above all. Hibernate raises it bare when
     * it fires on persist and wrapped when it fires on the commit flush, so
     * both arrive here. Nothing was written either way; what differs is whether
     * the caller can do anything about it.
     */
    @ExceptionHandler({ConstraintViolationException.class, TransactionSystemException.class})
    public Object handleConstraintViolation(RuntimeException ex, HttpServletRequest request,
                                            HttpServletResponse response, Model model) {
        String broken = brokenRule(ex);
        if (broken == null) {
            // Not a validation failure but a commit that genuinely fell over,
            // so it is ours, and it is a 500 however friendly the page looks.
            log.error("Transaction failed on {}", request.getRequestURI(), ex);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            String message = "That could not be saved. Nothing was changed - please try again.";
            return isApi(request)
                    ? ResponseEntity.internalServerError().body(Map.of("error", message))
                    : page(model, request, "That could not be saved", message);
        }
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return isApi(request)
                ? ResponseEntity.badRequest().body(Map.of("error", broken))
                : page(model, request, "That could not be saved", broken);
    }

    /** A key or a column the database defends and we did not check first. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Object handleDataIntegrity(DataIntegrityViolationException ex,
                                      HttpServletRequest request, Model model) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        String message = "That clashes with something already saved, so nothing was changed.";
        if (isApi(request)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
        }
        return page(model, request, "That could not be saved", message);
    }

    /**
     * An upload past the servlet limit, thrown by the multipart resolver before
     * any controller runs — so on the raise-a-bug form the whole report goes
     * with it, which is worth saying out loud rather than showing a 404.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Object handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request, Model model) {
        long limit = Math.round(attachments.getMaxSizeBytes() / (1024.0 * 1024.0));
        String message = "That upload was too large, so nothing on the form was saved. "
                + "Keep each file under " + limit + " MB, and attach a few at a time.";
        if (isApi(request)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", message));
        }
        return page(model, request, "That upload was too large", message);
    }

    /**
     * Caught ahead of the IllegalArgumentException below, which it extends, and
     * the reason that catch had to be narrowed: a malformed media type is our
     * data being wrong, not the caller's request. It used to answer an &lt;img&gt;
     * tag with 200 and an HTML page reading "Bug not found".
     */
    @ExceptionHandler(InvalidMediaTypeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Object handleInvalidMediaType(InvalidMediaTypeException ex,
                                         HttpServletRequest request, Model model) {
        log.error("Malformed content type \"{}\" on {}", ex.getMediaType(), request.getRequestURI(), ex);
        String message = "This file is stored with a content type the server cannot read.";
        if (isApi(request)) {
            return ResponseEntity.internalServerError().body(Map.of("error", message));
        }
        return page(model, request, "Something went wrong", message);
    }

    /**
     * The services raise this for input a person can fix — a project without a
     * name, a document over the size cap. It is logged as well as shown: it is
     * a broad net, and a genuine bug landing in it should still leave a trace.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleBadRequest(IllegalArgumentException ex, HttpServletRequest request, Model model) {
        log.warn("Rejected {}: {}", request.getRequestURI(), ex.getMessage());
        String message = said(ex, "That request could not be carried out.");
        if (isApi(request)) {
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
        return page(model, request, "That did not work", message);
    }

    /** Never null: an exception raised without a message must not take the error page down with it. */
    private static String said(Throwable ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }

    private static boolean isApi(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    /** The rules the save broke, in the words the entity puts on them, or null if it broke none. */
    private static String brokenRule(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violations
                    && !violations.getConstraintViolations().isEmpty()) {
                return violations.getConstraintViolations().stream()
                        .map(ConstraintViolation::getMessage)
                        .distinct()
                        .collect(Collectors.joining(" "));
            }
        }
        return null;
    }

    private String page(Model model, HttpServletRequest request, String heading, String message) {
        model.addAttribute("errorHeading", heading);
        model.addAttribute("errorMessage", message);
        navbar(model, request);
        return "error/not-found";
    }

    /**
     * Spring does not run GlobalModelAttributes for an @ExceptionHandler result,
     * so without this the error page renders with no project switcher and no
     * navbar counts — the one page where a way back matters most.
     */
    private void navbar(Model model, HttpServletRequest request) {
        try {
            model.addAttribute("unreadNotifications", globals.unreadNotifications(request));
            model.addAttribute("recentNotifications", globals.recentNotifications(request));
            model.addAttribute("trashCount", globals.trashCount(request));
            model.addAttribute("projectCounts", globals.projectCounts(request));
            model.addAttribute("currentProject", globals.currentProject(request, request.getSession()));
            model.addAttribute("currentPath", request.getRequestURI());
        } catch (RuntimeException e) {
            // Whatever failed may have taken the database with it. A bare
            // navbar still beats losing the error page to a second failure.
            log.debug("Could not build the navbar for the error page: {}", e.getMessage());
        }
    }
}
