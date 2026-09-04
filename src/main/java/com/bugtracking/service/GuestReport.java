package com.bugtracking.service;

import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a client may put in a report, and the whole of it.
 *
 * <p>A form object rather than the {@link com.bugtracking.model.Bug} entity,
 * and that is the point of the class. Binding the entity to a request means
 * every field on it is settable by whoever sent the request: the project, the
 * board column, the assignees, the blocker, the due date. On an internal form
 * that is only untidy, because everybody typing into it could set those from
 * the page next door anyway. On a form somebody outside the company is filling
 * in, it is the difference between a report and a way to assign work to a
 * developer on a project the sender was never given.
 *
 * <p>So the four fields below are what arrives, and everything else about the
 * bug — which project it lands on, which column it starts in, who it is
 * reported by, that it came from outside — is decided on the server from the
 * account that sent it.
 */
public class GuestReport {

    @NotBlank(message = "Give the report a title")
    @Size(max = 150, message = "Title must be 150 characters or fewer")
    private String title;

    @NotBlank(message = "Describe what happened")
    @Size(max = 4000, message = "That is longer than the report can hold")
    private String description;

    @NotNull(message = "Say how bad it is")
    private Severity severity = Severity.MEDIUM;

    @NotNull(message = "Say where you saw it")
    private Environment environment = Environment.PRODUCTION;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }
}
