package com.bugtracking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds the S3 client, and the presigner that makes time-limited download
 * links, from {@link S3Properties}.
 *
 * <p>The whole class is skipped unless {@code bugtracking.s3.enabled=true}, so
 * the default local-disk setup never touches AWS and never needs credentials.
 * Nothing consumes these beans yet — {@code AttachmentService} still reads and
 * writes {@code bugtracking.attachments.dir}. Wiring it across is the next step.
 *
 * <p>Both beans are thread-safe and meant to be held for the life of the
 * application; Spring closes them at shutdown.
 */
@Configuration
@ConditionalOnProperty(prefix = "bugtracking.s3", name = "enabled", havingValue = "true")
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    private final S3Properties properties;
    private final AwsCredentialsProvider credentials;

    public S3Config(S3Properties properties) {
        this.properties = properties;

        // Fail at startup rather than on the first upload: a bucket that is
        // only discovered to be missing when a user attaches a file is a much
        // worse way to learn about it.
        if (properties.getBucket().isBlank()) {
            throw new IllegalStateException(
                    "bugtracking.s3.enabled is true but bugtracking.s3.bucket is empty. "
                            + "Set S3_BUCKET in .env, or set bugtracking.s3.enabled=false to keep "
                            + "storing attachments on local disk.");
        }

        // Resolved once and shared: the client and the presigner must sign
        // with the same identity, and it keeps the log line below to one.
        this.credentials = resolveCredentials();
    }

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials);

        if (properties.hasCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        // Supabase Storage, MinIO and R2 only serve the path form; AWS serves
        // both but prefers the virtual-host form, so this stays off by default.
        builder.forcePathStyle(properties.isPathStyleAccess());

        log.info("S3 storage enabled: bucket {} in {}{}", properties.getBucket(), properties.getRegion(),
                properties.hasCustomEndpoint() ? " via " + properties.getEndpoint() : "");
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials);

        if (properties.hasCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        // The presigner signs the same URL shape the client would request, so
        // path style has to match or the signature will not verify.
        builder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.isPathStyleAccess())
                .build());

        return builder.build();
    }

    /**
     * A static key pair when one is configured, otherwise the AWS default
     * chain — environment variables, {@code ~/.aws/credentials}, an instance
     * role. The chain is preferred: it keeps keys out of files entirely.
     */
    private AwsCredentialsProvider resolveCredentials() {
        if (properties.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey()));
        }
        log.info("No S3 keys configured; using the AWS default credential chain.");
        return DefaultCredentialsProvider.builder().build();
    }
}
