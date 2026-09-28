package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.vo.ProcessingResponse;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ProcessingService {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingService.class);
    private final ProcessingTransactions transactions;
    public ProcessingService(ProcessingTransactions transactions) { this.transactions = transactions; }
    public ProcessingStart start(long owner, long project, long document, String clientRequestId) {
        if (clientRequestId == null || !clientRequestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        return database(() -> transactions.start(owner, project, document, UUID.fromString(clientRequestId).toString()));
    }
    public ProcessingResponse status(long owner, long project, long document) {
        return database(() -> transactions.status(owner, project, document));
    }
    private <T> T database(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | TransactionException failure) {
            LOG.warn("Processing state unconfirmed traceId={} code=KNOWLEDGE_DATABASE_UNAVAILABLE", MDC.get("traceId"));
            throw new BusinessException(ErrorCode.KNOWLEDGE_DATABASE_UNAVAILABLE);
        }
    }
}
