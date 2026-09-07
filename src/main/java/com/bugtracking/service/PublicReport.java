package com.bugtracking.service;

import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Closed like GuestReport: nothing that decides project, column or assignee binds here.
public class PublicReport {

    @NotBlank(message = "Tell us your name")
    @Size(max = 80, message = "Name must be 80 characters or fewer")
    private String reporterName;

    @NotBlank(message = "Tell us your email so the team can reach you")
    @Email(message = "That does not look like an email address")
    @Size(max = 200, message = "Email must be 200 characters or fewer")
    private String reporterEmail;

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

    public String getReporterName() {
        return reporterName;
    }

    public void setReporterName(String reporterName) {
        this.reporterName = reporterName;
    }

    public String getReporterEmail() {
        return reporterEmail;
    }

    public void setReporterEmail(String reporterEmail) {
        this.reporterEmail = reporterEmail;
    }

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
