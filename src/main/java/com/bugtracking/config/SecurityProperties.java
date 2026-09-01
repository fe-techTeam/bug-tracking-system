package com.bugtracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The single sign-in account, from {@code bugtracking.security} in
 * application.properties.
 *
 * <p>The password is written here in plain text because this is a local
 * development app with one account. It is hashed with BCrypt when the account
 * is built at startup and the hash is what any comparison runs against — the
 * plain value never leaves this object. For anything beyond local use, move
 * accounts into the database with stored hashes.
 */
@Component
@ConfigurationProperties(prefix = "bugtracking.security")
public class SecurityProperties {

    private String email = "";

    private String password = "";

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
