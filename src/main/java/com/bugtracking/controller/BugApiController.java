package com.bugtracking.controller;

import com.bugtracking.config.ClientProperties;
import com.bugtracking.model.Bug;
import com.bugtracking.model.Severity;
import com.bugtracking.model.Status;
import com.bugtracking.service.BugService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** JSON API — handy for raising bugs from scripts or automated tests. */
@RestController
@RequestMapping("/api/bugs")
public class BugApiController {

    private final BugService service;
    private final ClientProperties clientProperties;

    public BugApiController(BugService service, ClientProperties clientProperties) {
        this.service = service;
        this.clientProperties = clientProperties;
    }

    /** The client names a script is allowed to send in the required "client" field. */
    @GetMapping("/clients")
    public List<String> clients() {
        return clientProperties.getClients();
    }

    @GetMapping
    public List<Bug> list(@RequestParam(required = false) Status status,
                          @RequestParam(required = false) Severity severity,
                          @RequestParam(required = false) String keyword) {
        return service.findAll(status, severity, keyword);
    }

    @GetMapping("/{id}")
    public Bug get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Bug create(@Valid @RequestBody Bug bug) {
        return service.save(bug);
    }

    @PutMapping("/{id}")
    public Bug update(@PathVariable Long id, @Valid @RequestBody Bug bug) {
        return service.update(id, bug);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "byStatus", service.statusSummary(),
                "bySeverity", service.severitySummary());
    }
}
