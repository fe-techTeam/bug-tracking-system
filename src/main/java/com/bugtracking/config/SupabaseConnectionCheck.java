package com.bugtracking.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refuses to start with the database connection settings missing, and says
 * which ones.
 *
 * <p>Without this, an unset variable is left in the property as the literal
 * text {@code ${SUPABASE_DB_HOST}} and surfaces much later as
 * {@code UnknownHostException: ${SUPABASE_DB_HOST}} — which reads like a
 * network problem rather than an empty line in .env.
 *
 * <p>It is a {@link BeanFactoryPostProcessor} so that it runs before the
 * DataSource is built, rather than racing it.
 */
@Component
public class SupabaseConnectionCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Property to check, and the .env key a reader should go and fill in. */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("spring.datasource.url", "SUPABASE_DB_HOST");
        REQUIRED.put("spring.datasource.username", "SUPABASE_DB_USER");
        REQUIRED.put("spring.datasource.password", "SUPABASE_DB_PASSWORD");
    }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> missing = new ArrayList<>();

        REQUIRED.forEach((property, envKey) -> {
            String value;
            try {
                value = environment.getProperty(property);
            } catch (IllegalArgumentException e) {
                // Reading a property whose placeholder cannot be resolved
                // throws rather than handing back the literal ${...}, so an
                // unset variable arrives here as an exception.
                missing.add(envKey);
                return;
            }
            // Belt and braces: some binding paths do pass the raw placeholder
            // through instead, which then shows up as a nonsense hostname.
            if (value == null || value.isBlank() || value.contains("${")) {
                missing.add(envKey);
            }
        });

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The Supabase database is the only one this app has, and these are not set: "
                            + String.join(", ", missing)
                            + ". Copy .env.example to .env and fill in the Supabase section "
                            + "(Dashboard > Project Settings > Database > Connection string > JDBC). "
                            + "There is no local database to fall back to - that is deliberate, so a "
                            + "checkout cannot quietly collect projects nobody else can see.");
        }
    }
}
