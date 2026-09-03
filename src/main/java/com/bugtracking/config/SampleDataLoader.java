package com.bugtracking.config;

import com.bugtracking.model.Bug;
import com.bugtracking.model.Environment;
import com.bugtracking.model.Severity;
import com.bugtracking.repository.BugRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds a few example bugs the first time the app starts on an empty database.
 *
 * <p>Each one is written the way the form now asks for it: a single report that
 * says what happens, how to see it, and what should have happened instead.
 */
@Configuration
public class SampleDataLoader {

    @Bean
    CommandLineRunner seedBugs(BugRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            repository.save(newBug(
                    "Login button does nothing on first click",
                    """
                    On the sign-in page the first click on Login is ignored; the second click works.

                    How to see it:
                    1. Open /login
                    2. Enter valid credentials
                    3. Click Login once

                    Expected: user is signed in and lands on the dashboard.
                    Actual: nothing happens; the page stays on /login.""",
                    Severity.HIGH, "OPEN", Environment.UAT,
                    "Mahindra Mutual Fund", "Authentication", "nishana", "dev-team"));

            repository.save(newBug(
                    "NAV values not loading on Holdings page",
                    """
                    The NAV column shows a spinner forever for folios with more than 10 schemes.

                    How to see it:
                    1. Log in as an investor
                    2. Open Holdings
                    3. Select a folio with 10+ schemes

                    Expected: NAV values render within 3 seconds.
                    Actual: spinner never resolves; console shows a 504 from /api/nav.""",
                    Severity.CRITICAL, "IN_PROGRESS", Environment.PRODUCTION,
                    "Godrej", "Investor", "nishana", "backend-team"));

            repository.save(newBug(
                    "Typo on About Us page",
                    """
                    The heading reads 'Abuot Us' instead of 'About Us'.

                    How to see it:
                    1. Open /about-us
                    2. Look at the main heading

                    Expected: heading reads 'About Us'.""",
                    Severity.LOW, "READY_FOR_TEST", Environment.QA,
                    "Color Shine", "Public Site", "qa-team", "content-team"));

            repository.save(newBug(
                    "Session expires after 2 minutes of inactivity",
                    """
                    Users are logged out much sooner than the documented 30 minute timeout.

                    How to see it:
                    1. Log in
                    2. Leave the tab idle for 2 minutes
                    3. Click any menu item

                    Expected: session stays alive for 30 minutes.
                    Actual: redirected to the login page with 'Session expired'.""",
                    Severity.MEDIUM, "IN_PROGRESS", "Orpat", "Authentication", "qa-team", null));
        };
    }

    private Bug newBug(String title, String description, Severity severity, String status,
                       String project, String module, String reportedBy, String assignedTo) {
        return newBug(title, description, severity, status, Environment.QA,
                project, module, reportedBy, assignedTo);
    }

    private Bug newBug(String title, String description, Severity severity, String status,
                       Environment environment, String project, String module,
                       String reportedBy, String assignedTo) {
        Bug bug = new Bug();
        bug.setEnvironment(environment);
        bug.setProject(project);
        bug.setTitle(title);
        bug.setDescription(description);
        bug.setSeverity(severity);
        bug.setStatus(status);
        bug.setModule(module);
        bug.setReportedBy(reportedBy);
        bug.setAssignedTo(assignedTo);
        return bug;
    }
}
