package com.devmate.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strategy, resource limits and retry timings are fixed by DEV-017, not client parameters. */
@ConfigurationProperties(prefix = "devmate.knowledge.processing")
public class ProcessingProperties {
    private volatile boolean enabled;
    private boolean schedulingEnabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean value) { schedulingEnabled = value; }
}
