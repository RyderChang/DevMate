package com.devmate.knowledge.application;

import com.devmate.common.api.*;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.DocumentMapper;
import com.devmate.knowledge.vo.DocumentResponse;
import com.devmate.project.service.ProjectService;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);
    private final ProjectService projects;
    private final DocumentTransactions transactions;
    private final DocumentMapper mapper;
    private final ObjectStorage storage;
    private final DocumentValidator validator;
    private final KnowledgeProperties properties;
    private final UploadSlots slots;
    public DocumentService(ProjectService projects, DocumentTransactions transactions, DocumentMapper mapper,
                           ObjectStorage storage, DocumentValidator validator, KnowledgeProperties properties, UploadSlots slots) {
        this.projects = projects; this.transactions = transactions; this.mapper = mapper; this.storage = storage;
        this.validator = validator; this.properties = properties; this.slots = slots;
    }

    public DocumentResponse upload(long owner, long project, String clientRequestId, MultipartFile file) {
        return database(() -> {
            projects.requireOwnedActiveProject(owner, project);
            if (!properties.isEnabled()) throw new BusinessException(ErrorCode.KNOWLEDGE_SERVICE_DISABLED);
            String request = requestId(clientRequestId);
            try (var slot = slots.acquire()) {
                ValidatedDocument input = validator.prepare(file);
                try {
                    Reservation reservation = transactions.reserve(owner, project, request, input);
                    if (!reservation.created()) return reservation.document().response();
                    var row = transactions.startPut(reservation.document().id, reservation.document().operationVersion, reservation.document().putToken);
                    try { storage.put(row.location(), input.path(), row.byteSize, row.sha256, row.putToken); }
                    catch (StorageFailure failure) {
                        if (failure.definitelyNoWrite()) {
                            transactions.acknowledgePut(row.id, row.putToken);
                            transactions.finishUpload(row.id, row.putToken, false);
                        }
                        throw new BusinessException(failure.errorCode());
                    }
                    transactions.acknowledgePut(row.id, row.putToken);
                    var result = transactions.finishUpload(row.id, row.putToken, true);
                    projects.requireOwnedActiveProject(owner, project);
                    if (result.storageState != StorageState.STORED) throw new BusinessException(ErrorCode.DOCUMENT_REQUEST_TERMINATED);
                    LOG.info("Document operation traceId={} documentId={} operation=upload state=STORED", MDC.get("traceId"), row.id);
                    return result.response();
                } finally { validator.release(input); }
            }
        });
    }

    public PageResult<DocumentResponse> list(long owner, long project, int page, int pageSize) {
        return database(() -> {
            projects.requireOwnedActiveProject(owner, project);
            if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 100) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            return new PageResult<>(page, pageSize, mapper.countVisible(owner, project),
                    mapper.visiblePage(owner, project, ((long) page - 1) * pageSize, pageSize).stream().map(row -> row.response()).toList());
        });
    }
    public DocumentResponse get(long owner, long project, long id) {
        return database(() -> {
            projects.requireOwnedActiveProject(owner, project);
            if (id < 1) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            var row = mapper.visible(owner, project, id);
            if (row == null) throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
            return row.response();
        });
    }
    public void delete(long owner, long project, long id) {
        database(() -> {
            if (id < 1) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
            transactions.markDelete(owner, project, id);
            LOG.info("Document operation traceId={} documentId={} operation=delete state=DELETE_PENDING", MDC.get("traceId"), id);
            return null;
        });
    }
    private String requestId(String input) {
        if (input == null || !input.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        return UUID.fromString(input).toString();
    }
    private <T> T database(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | TransactionException error) {
            LOG.warn("Document database result uncertain traceId={} code=KNOWLEDGE_DATABASE_UNAVAILABLE", MDC.get("traceId"));
            throw new BusinessException(ErrorCode.KNOWLEDGE_DATABASE_UNAVAILABLE);
        }
    }
}
