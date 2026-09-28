package com.devmate.knowledge.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devmate.knowledge")
public class KnowledgeProperties implements InitializingBean {
    private boolean enabled = false;
    private boolean allowLocalHttp = false;
    private boolean schedulingEnabled = true;
    private String endpoint = "";
    private String region = "us-east-1";
    private String bucket = "";
    private String accessKey = "";
    private String secretKey = "";
    private long maxFileBytes = 5L * 1024 * 1024;
    private long maxRequestBytes = 6L * 1024 * 1024;
    private long tempMaxBytes = 256L * 1024 * 1024;
    private int maxDocuments = 100;
    private long maxProjectBytes = 100L * 1024 * 1024;
    private int maxConcurrentUploads = 4;
    private int scanBatchSize = 50;
    private int maxAutomaticRetries = 5;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(10);
    private Duration operationTimeout = Duration.ofSeconds(30);
    private Duration operationLease = Duration.ofMinutes(2);
    private Duration scanInterval = Duration.ofSeconds(60);
    private String tempDirectory = System.getProperty("java.io.tmpdir") + "/devmate-knowledge";

    @Override public void afterPropertiesSet() {
        if (maxFileBytes < 1 || maxFileBytes > 5L * 1024 * 1024
                || maxRequestBytes <= maxFileBytes || maxRequestBytes > 6L * 1024 * 1024
                || tempMaxBytes < maxRequestBytes + maxFileBytes + 1024 || tempMaxBytes > 256L * 1024 * 1024
                || maxDocuments < 1 || maxDocuments > 100 || maxProjectBytes < 1 || maxProjectBytes > 100L * 1024 * 1024
                || maxConcurrentUploads < 1 || maxConcurrentUploads > 4 || scanBatchSize < 1 || scanBatchSize > 50
                || maxAutomaticRetries < 1 || maxAutomaticRetries > 5 || tempDirectory == null || tempDirectory.isBlank()) {
            throw invalid();
        }
        duration(connectTimeout, Duration.ofSeconds(30));
        duration(readTimeout, Duration.ofSeconds(30));
        duration(operationTimeout, Duration.ofSeconds(30));
        duration(operationLease, Duration.ofMinutes(10));
        duration(scanInterval, Duration.ofMinutes(10));
        if (operationLease.compareTo(operationTimeout.plusSeconds(30)) < 0
                || connectTimeout.compareTo(operationTimeout) > 0 || readTimeout.compareTo(operationTimeout) > 0) throw invalid();
        if (!enabled) return;
        URI uri;
        try { uri = URI.create(endpoint); } catch (RuntimeException error) { throw invalid(); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !(uri.getScheme().equals("https") || (allowLocalHttp && uri.getScheme().equals("http")))
                || region == null || !region.matches("[a-z0-9-]{1,40}")
                || bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")
                || accessKey == null || secretKey == null || accessKey.isBlank() || secretKey.isBlank()
                || !accessKey.equals(accessKey.strip()) || !secretKey.equals(secretKey.strip())) throw invalid();
    }
    private void duration(Duration value, Duration max) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(max) > 0) throw invalid();
    }
    private IllegalStateException invalid() { return new IllegalStateException("Invalid devmate.knowledge configuration"); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isAllowLocalHttp() { return allowLocalHttp; }
    public void setAllowLocalHttp(boolean value) { allowLocalHttp = value; }
    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean value) { schedulingEnabled = value; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getRegion() { return region; }
    public void setRegion(String value) { region = value; }
    public String getBucket() { return bucket; }
    public void setBucket(String value) { bucket = value; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String value) { accessKey = value; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String value) { secretKey = value; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long value) { maxFileBytes = value; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long value) { maxRequestBytes = value; }
    public long getTempMaxBytes() { return tempMaxBytes; }
    public void setTempMaxBytes(long value) { tempMaxBytes = value; }
    public int getMaxDocuments() { return maxDocuments; }
    public void setMaxDocuments(int value) { maxDocuments = value; }
    public long getMaxProjectBytes() { return maxProjectBytes; }
    public void setMaxProjectBytes(long value) { maxProjectBytes = value; }
    public int getMaxConcurrentUploads() { return maxConcurrentUploads; }
    public void setMaxConcurrentUploads(int value) { maxConcurrentUploads = value; }
    public int getScanBatchSize() { return scanBatchSize; }
    public void setScanBatchSize(int value) { scanBatchSize = value; }
    public int getMaxAutomaticRetries() { return maxAutomaticRetries; }
    public void setMaxAutomaticRetries(int value) { maxAutomaticRetries = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public Duration getOperationTimeout() { return operationTimeout; }
    public void setOperationTimeout(Duration value) { operationTimeout = value; }
    public Duration getOperationLease() { return operationLease; }
    public void setOperationLease(Duration value) { operationLease = value; }
    public Duration getScanInterval() { return scanInterval; }
    public void setScanInterval(Duration value) { scanInterval = value; }
    public String getTempDirectory() { return tempDirectory; }
    public void setTempDirectory(String value) { tempDirectory = value; }
}
