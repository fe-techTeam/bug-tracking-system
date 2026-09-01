package com.bugtracking.controller;

import com.bugtracking.service.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;
import java.util.NoSuchElementException;

/** Turns "bug not found" into a friendly page for the UI and a 404 for the API. */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public Object handleNotFound(NoSuchElementException ex, HttpServletRequest request, Model model) {
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", ex.getMessage()));
        }
        model.addAttribute("errorMessage", ex.getMessage());
        return "error/not-found";
    }

    /**
     * A file the caller could fix by choosing a different one, or a malformed
     * request body, is a 400 — not a 500.
     */
    @ExceptionHandler({AttachmentService.RejectedFileException.class, IllegalArgumentException.class})
    public Object handleBadRequest(RuntimeException ex, HttpServletRequest request, Model model) {
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        model.addAttribute("errorMessage", ex.getMessage());
        return "error/not-found";
    }

    /** An upload larger than the servlet limit, before it ever reaches the service. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request, Model model) {
        String message = "That file is too large to upload.";
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", message));
        }
        model.addAttribute("errorMessage", message);
        return "error/not-found";
    }
}
