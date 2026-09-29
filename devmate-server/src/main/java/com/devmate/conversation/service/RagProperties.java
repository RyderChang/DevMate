package com.devmate.conversation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR 0008 limits are fixed; configuration only controls admission and recovery scheduling. */
@ConfigurationProperties(prefix = "devmate.conversation.rag")
public class RagProperties {
    private boolean enabled;
    private boolean schedulingEnabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean value) { schedulingEnabled = value; }
}
