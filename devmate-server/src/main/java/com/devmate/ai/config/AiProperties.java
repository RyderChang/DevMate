package com.devmate.ai.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devmate.ai")
public class AiProperties implements InitializingBean {
    private boolean enabled;
    private String provider = "openai";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(60);
    private int maxOutputTokens = 1024;
    private int maxMessageCharacters = 8000;
    private int maxContextMessages = 20;
    private int maxContextCharacters = 24000;
    private Duration generationLease = Duration.ofMinutes(2);
    private int maxResponseBytes = 1_048_576;
    private final OpenAi openai = new OpenAi();
    private final DeepSeek deepseek = new DeepSeek();

    @Override
    public void afterPropertiesSet() {
        requireDuration(connectTimeout, Duration.ofSeconds(30), "connect-timeout");
        requireDuration(readTimeout, Duration.ofSeconds(120), "read-timeout");
        if (maxOutputTokens < 1 || maxOutputTokens > 4096
                || maxMessageCharacters < 1 || maxMessageCharacters > 8000
                || maxContextMessages < 1 || maxContextMessages > 50
                || maxContextCharacters < 1 || maxContextCharacters > 500_000
                || maxResponseBytes < 1024 || maxResponseBytes > 5_242_880) {
            throw new IllegalStateException("Invalid devmate.ai limit configuration");
        }
        if (generationLease == null || !generationLease.minus(readTimeout).isPositive()) {
            throw new IllegalStateException("devmate.ai.generation-lease must be greater than read-timeout");
        }
        if (!"openai".equals(provider) && !"deepseek".equals(provider)) {
            throw new IllegalStateException("devmate.ai.provider must be openai or deepseek");
        }
        if ("deepseek".equals(provider)) {
            validateDeepSeek();
            return;
        }
        URI baseUri;
        try {
            baseUri = URI.create(openai.baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("devmate.ai.openai.base-url must be a valid URI", exception);
        }
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalStateException("devmate.ai.openai.base-url must use HTTP or HTTPS");
        }
        if (enabled && (!"openai".equals(provider) || isBlank(openai.apiKey) || isBlank(openai.model)
                || !openai.apiKey.equals(openai.apiKey.strip())
                || !openai.model.equals(openai.model.strip()) || openai.model.length() > 100)) {
            throw new IllegalStateException("Enabled AI requires provider=openai, api-key and model");
        }
    }

    private void validateDeepSeek() {
        URI uri;
        try {
            uri = URI.create(deepseek.baseUrl);
        } catch (RuntimeException exception) {
            // Configuration errors must not echo credentials embedded in an invalid URI.
            throw new IllegalStateException("Invalid devmate.ai.deepseek.base-url");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.deepseek.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/") || uri.getPath().equals("/v1")
                || uri.getPath().equals("/v1/"))) {
            throw new IllegalStateException("DeepSeek requires the official HTTPS API base URL");
        }
        if (enabled && (isBlank(deepseek.apiKey) || isBlank(deepseek.model)
                || !deepseek.apiKey.equals(deepseek.apiKey.strip()) || deepseek.apiKey.chars().anyMatch(Character::isISOControl)
                || !deepseek.model.equals(deepseek.model.strip()) || deepseek.model.length() > 100
                || deepseek.model.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalStateException("Enabled DeepSeek requires api-key and model");
        }
    }

    private void requireDuration(Duration value, Duration maximum, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("devmate.ai." + name + " must be positive and at most " + maximum);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getMaxMessageCharacters() { return maxMessageCharacters; }
    public void setMaxMessageCharacters(int maxMessageCharacters) { this.maxMessageCharacters = maxMessageCharacters; }
    public int getMaxContextMessages() { return maxContextMessages; }
    public void setMaxContextMessages(int maxContextMessages) { this.maxContextMessages = maxContextMessages; }
    public int getMaxContextCharacters() { return maxContextCharacters; }
    public void setMaxContextCharacters(int maxContextCharacters) { this.maxContextCharacters = maxContextCharacters; }
    public Duration getGenerationLease() { return generationLease; }
    public void setGenerationLease(Duration generationLease) { this.generationLease = generationLease; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public OpenAi getOpenai() { return openai; }
    public DeepSeek getDeepseek() { return deepseek; }
    public String selectedModel() { return "deepseek".equals(provider) ? deepseek.model : openai.model; }

    public static class OpenAi {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
