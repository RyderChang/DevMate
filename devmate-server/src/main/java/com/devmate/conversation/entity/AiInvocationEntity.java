package com.devmate.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("ai_invocations")
public class AiInvocationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String clientRequestId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String provider;
    private String model;
    private String promptTemplateVersion;
    private String providerRequestId;
    private String status;
    private String errorCode;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Long getUserMessageId() { return userMessageId; }
    public void setUserMessageId(Long userMessageId) { this.userMessageId = userMessageId; }
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptTemplateVersion() { return promptTemplateVersion; }
    public void setPromptTemplateVersion(String promptTemplateVersion) { this.promptTemplateVersion = promptTemplateVersion; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
