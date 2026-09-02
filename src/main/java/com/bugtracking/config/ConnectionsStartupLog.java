package com.bugtracking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * One line at startup naming the database and the attachment store that are
 * actually live.
 *
 * <p>With H2, Supabase and S3 all configurable, "which one am I talking to?"
 * stops being obvious — and a profile that silently failed to activate looks
 * exactly like one that worked until data goes missing. This answers it in the
 * log, from the real connection rather than from the properties that were
 * meant to shape it.
 */
@Component
public class ConnectionsStartupLog {

    private static final Logger log = LoggerFactory.getLogger(ConnectionsStartupLog.class);

    private final DataSource dataSource;
    private final AttachmentProperties attachments;
    private final S3Properties s3;

    public ConnectionsStartupLog(DataSource dataSource, AttachmentProperties attachments, S3Properties s3) {
        this.dataSource = dataSource;
        this.attachments = attachments;
        this.s3 = s3;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        log.info("Database: {}", describeDatabase());
        log.info("Attachments: {}", describeStorage());
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
