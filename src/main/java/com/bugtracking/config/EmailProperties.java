package com.bugtracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * What the app puts on an email, and whether it sends one at all.
 *
 * <p>Deliberately separate from {@code spring.mail.*}, which is the
 * <em>connection</em> — host, port, credentials, TLS. That belongs to Spring
 * and is configured in {@code .env}; this is the half that is about this app:
 * who the message is from, where the links point, and the master switch.
 *
 * <p>{@link #isEnabled()} defaults to false, so a checkout with no mail server
 * behaves exactly as it did before there was one: notifications appear in the
 * bell and nothing leaves the machine.
 */
@Component
@ConfigurationProperties(prefix = "bugtracking.mail")
public class EmailProperties {

    /**
     * The master switch. Off means no message is built and no connection is
     * opened, whatever {@code spring.mail.host} says.
     *
     * <p>Two switches rather than one, because they fail differently: an
     * unreachable host is a misconfiguration worth a warning in the log, and
     * "we do not send email here" is not.
     */
    private boolean enabled = false;

    /** The From address. Required when enabled; many providers reject anything else. */
    private String from = "";

    /** The name beside it, so an inbox shows "Bug Tracking" rather than a bare address. */
    private String fromName = "Bug Tracking";

    /** Where a reply should go, if not to the From address. Optional. */
    private String replyTo = "";

    /**
     * Where this instance is reachable, for the link in every message.
     *
     * <p>Not derived from the request: an email is built after the response has
     * gone, often on another thread, and a link built from "localhost" is a
     * link that works for exactly one reader.
     */
    private String baseUrl = "http://localhost:8085";

    /** Prefixed to every subject, so an inbox rule has something to match on. */
    private String subjectPrefix = "[Bug Tracking]";

    /**
     * Whether the whole report travels with the notification.
     *
     * <p>On, because the point of the email is that a developer reading it on a
     * phone knows what the bug is without opening anything. Off is for an
     * instance whose bug titles are the most that should sit in an inbox.
     */
    private boolean includeDetails = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public boolean isIncludeDetails() {
        return includeDetails;
    }

    public void setIncludeDetails(boolean includeDetails) {
        this.includeDetails = includeDetails;
    }

    /** The base URL with any trailing slash removed, for building a link onto. */
    public String trimmedBaseUrl() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
