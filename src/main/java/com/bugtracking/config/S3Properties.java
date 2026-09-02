package com.bugtracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Where attachment bytes go when a bucket is used instead of local disk, from
 * {@code bugtracking.s3} in application.properties.
 *
 * <p>The settings that are secrets — the two keys — are read from environment
 * variables or the gitignored {@code .env}, never written into the committed
 * properties file. Leaving both blank is the better option where it is
 * possible: the AWS default credential chain then supplies them.
 */
@Component
@ConfigurationProperties(prefix = "bugtracking.s3")
public class S3Properties {

    /** Nothing is built and no credentials are needed while this is false. */
    private boolean enabled = false;

    /** The bucket attachments are written to. Required once enabled. */
    private String bucket = "";

    /** AWS region of the bucket. Any non-blank value will do for S3-compatible services. */
    private String region = "ap-south-1";

    /**
     * Blank for real AWS. Set for anything else that speaks S3 — Supabase
     * Storage, MinIO, R2 — in which case turn on {@link #pathStyleAccess} too.
     */
    private String endpoint = "";

    /**
     * Puts the bucket in the path ({@code host/bucket/key}) rather than the
     * hostname. AWS prefers the hostname form; most S3-compatible services
     * only support this one.
     */
    private boolean pathStyleAccess = false;

    /** Blank to use the AWS default credential chain instead of a static key. */
    private String accessKeyId = "";

    /** Blank to use the AWS default credential chain instead of a static key. */
    private String secretAccessKey = "";

    /** Every object key is written under this prefix, so a bucket can be shared. */
    private String keyPrefix = "attachments/";

    /** How long a generated download link stays valid. */
    private Duration presignedUrlExpiry = Duration.ofMinutes(15);

    /** True when a key pair was configured, rather than leaving it to the credential chain. */
    public boolean hasStaticCredentials() {
        return !accessKeyId.isBlank() && !secretAccessKey.isBlank();
    }

    /** True when pointed at something other than AWS. */
    public boolean hasCustomEndpoint() {
        return !endpoint.isBlank();
    }

    /** Prefixes an object key, collapsing the slash so the prefix can be written either way. */
    public String key(String storedName) {
        if (keyPrefix.isBlank()) {
            return storedName;
        }
        return keyPrefix.endsWith("/") ? keyPrefix + storedName : keyPrefix + "/" + storedName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(String secretAccessKey) {
        this.secretAccessKey = secretAccessKey;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getPresignedUrlExpiry() {
        return presignedUrlExpiry;
    }

    public void setPresignedUrlExpiry(Duration presignedUrlExpiry) {
        this.presignedUrlExpiry = presignedUrlExpiry;
    }
}
