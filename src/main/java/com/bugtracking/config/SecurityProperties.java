package com.bugtracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The sign-in accounts, from {@code bugtracking.security} in
 * application.properties.
 *
 * <p>Passwords are written there in plain text because this is a local
 * development app. Each one is hashed with BCrypt when the accounts are built
 * at startup and the hash is what any comparison runs against — the plain
 * value never leaves this object, except through {@link #isQuickFill()}. For
 * anything beyond local use, move accounts into the database with stored
 * hashes.
 */
@Component
@ConfigurationProperties(prefix = "bugtracking.security")
public class SecurityProperties {

    private List<Account> accounts = new ArrayList<>();

    /** Whether the sign-in page offers those accounts behind a click on its heading. */
    private boolean quickFill = true;

    public List<Account> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<Account> accounts) {
        this.accounts = accounts;
    }

    public boolean isQuickFill() {
        return quickFill;
    }

    public void setQuickFill(boolean quickFill) {
        this.quickFill = quickFill;
    }

    public static class Account {

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
}
