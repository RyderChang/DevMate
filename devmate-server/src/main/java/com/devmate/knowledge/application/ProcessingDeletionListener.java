package com.devmate.knowledge.application;

import com.devmate.project.service.ProjectDeleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ProcessingDeletionListener {
    private final ProcessingTransactions transactions;
    public ProcessingDeletionListener(ProcessingTransactions transactions) { this.transactions = transactions; }
    @EventListener public void deleted(ProjectDeleted event) {
        transactions.cancelProject(event.ownerUserId(), event.projectId());
    }
}
