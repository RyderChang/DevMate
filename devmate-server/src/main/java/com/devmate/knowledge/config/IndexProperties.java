package com.devmate.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devmate.knowledge.indexing")
public class IndexProperties {
    private boolean enabled;
    private boolean schedulingEnabled=true;
    private String modelOrigin="http://127.0.0.1:8091";
    private String vectorOrigin="http://127.0.0.1:6333";
    private String collection="devmate_qwen3_v1";
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
    public boolean isSchedulingEnabled(){return schedulingEnabled;} public void setSchedulingEnabled(boolean value){schedulingEnabled=value;}
    public String getModelOrigin(){return modelOrigin;} public void setModelOrigin(String value){modelOrigin=value;}
    public String getVectorOrigin(){return vectorOrigin;} public void setVectorOrigin(String value){vectorOrigin=value;}
    public String getCollection(){return collection;} public void setCollection(String value){collection=value;}
}
