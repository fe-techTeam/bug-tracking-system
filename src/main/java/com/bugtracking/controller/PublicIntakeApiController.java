package com.bugtracking.controller;

import com.bugtracking.service.AttachmentService;
import com.bugtracking.service.GuestRateLimit;
import com.bugtracking.service.GuestService;
import com.bugtracking.service.PublicIntakeService;
import com.bugtracking.service.PublicReport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/** The public intake form's route for machines: a project's token is the whole grant. */
@RestController
@RequestMapping("/api/public/{token}/bugs")
public class PublicIntakeApiController {

    private final PublicIntakeService intake;

    public PublicIntakeApiController(PublicIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> raise(@PathVariable String token,
                                                     @Valid @ModelAttribute PublicReport report,
                                                     BindingResult result,
                                                     @RequestParam(value = "files", required = false) MultipartFile[] files,
                                                     HttpServletRequest request) {
        if (intake.byToken(token).isEmpty()) {
            return unavailable();
        }
        if (result.hasErrors()) {
            Map<String, Object> errors = new LinkedHashMap<>();
            for (FieldError error : result.getFieldErrors()) {
                errors.putIfAbsent(error.getField(), error.getDefaultMessage());
            }
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        GuestService.Filed filed = intake.raise(token, report, files,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bugId", filed.bugId());
        body.put("rejected", filed.rejected());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // Handled here rather than in GlobalExceptionHandler, which answers with the app's HTML error page.
    @ExceptionHandler(PublicIntakeService.UnknownLinkException.class)
    public ResponseEntity<Map<String, Object>> unknownLink() {
        return unavailable();
    }

    @ExceptionHandler(GuestRateLimit.TooOftenException.class)
    public ResponseEntity<Map<String, Object>> tooOften(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error(e.getMessage()));
    }

    @ExceptionHandler({AttachmentService.RejectedFileException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> refused(RuntimeException e) {
        return ResponseEntity.badRequest().body(error(e.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("This link is not valid any more."));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
