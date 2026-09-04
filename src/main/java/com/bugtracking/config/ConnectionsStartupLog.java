package com.bugtracking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bugtracking.service.EmailService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * One line at startup naming the database, the attachment store and the mail
 * setup that are actually live.
 *
 * <p>With H2, Supabase and S3 all configurable, "which one am I talking to?"
 * stops being obvious — and a profile that silently failed to activate looks
 * exactly like one that worked until data goes missing. This answers it in the
 * log, from the real connection rather than from the properties that were
 * meant to shape it.
 *
 * <p>Mail is here for the same reason and one more: Settings used to carry a
 * tab that said this and offered a test send, which was a screen to hold one
 * button. Whether email goes anywhere is decided entirely by {@code .env}, so
 * the answer belongs beside the other two things {@code .env} decides.
 */
@Component
public class ConnectionsStartupLog {

    private static final Logger log = LoggerFactory.getLogger(ConnectionsStartupLog.class);

    private final DataSource dataSource;
    private final AttachmentProperties attachments;
    private final S3Properties s3;
    private final EmailService email;

    public ConnectionsStartupLog(DataSource dataSource, AttachmentProperties attachments,
                                 S3Properties s3, EmailService email) {
        this.dataSource = dataSource;
        this.attachments = attachments;
        this.s3 = s3;
        this.email = email;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        log.info("Database: {}", describeDatabase());
        log.info("Attachments: {}", describeStorage());
        log.info("Email: {}", email.describe());
    }

    private String describeDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            // The JDBC URL carries the host and database name but never the
            // password — that is a separate property — so it is safe to log.
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion()
                    + "  " + meta.getURL();
        } catch (SQLException e) {
            return "could not be reached - " + e.getMessage();
        }
    }

    private String describeStorage() {
        if (s3.isEnabled()) {
            return "s3://" + s3.getBucket() + "/" + s3.getKeyPrefix()
                    + (s3.hasCustomEndpoint() ? "  via " + s3.getEndpoint() : "  on AWS " + s3.getRegion());
        }
        return "local disk at " + attachments.getDir();
    }
}
