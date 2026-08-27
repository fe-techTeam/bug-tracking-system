package com.bugtracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The clients that can be picked when raising a bug, read from
 * {@code bugtracking.clients} in application.properties. Kept in configuration
 * rather than an enum so the list can change without a code change.
 */
@Component
@ConfigurationProperties(prefix = "bugtracking")
public class ClientProperties {

    private List<String> clients = new ArrayList<>();

    /** Filled in for bugs raised before the client field existed. */
    private String defaultClient = "Unspecified";

    public List<String> getClients() {
        return clients;
    }

    public void setClients(List<String> clients) {
        this.clients = clients;
    }

    public String getDefaultClient() {
        return defaultClient;
    }

    public void setDefaultClient(String defaultClient) {
        this.defaultClient = defaultClient;
    }
}
