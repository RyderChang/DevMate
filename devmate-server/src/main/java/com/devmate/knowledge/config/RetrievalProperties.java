package com.devmate.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="devmate.knowledge.retrieval")
public class RetrievalProperties {
    private boolean enabled;
    private boolean schedulingEnabled=true;
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;}
    public boolean isSchedulingEnabled(){return schedulingEnabled;}
    public void setSchedulingEnabled(boolean value){schedulingEnabled=value;}
}
