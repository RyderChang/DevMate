package com.devmate.knowledge;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.application.*;
import com.devmate.knowledge.config.KnowledgeProperties;
import com.devmate.knowledge.infrastructure.*;
import com.devmate.project.service.ProjectService;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DocumentServiceTest {
    @Mock ProjectService projects;
    @Mock DocumentTransactions transactions;
    @Mock DocumentMapper mapper;
    @Mock ObjectStorage storage;
    @Mock DocumentValidator validator;
    @Mock UploadSlots slots;
    KnowledgeProperties properties;
    DocumentService service;
    @BeforeEach void setup() {
        properties = new KnowledgeProperties(); properties.setEnabled(true);
        service = new DocumentService(projects, transactions, mapper, storage, validator, properties, slots);
    }
    @Test void ownershipRefusalPrecedesFileValidationAndAnyStorageOperation() {
        doThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND)).when(projects).requireOwnedActiveProject(1L, 2L);
        assertThatThrownBy(() -> service.upload(1, 2, java.util.UUID.randomUUID().toString(), file())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage, validator, transactions, slots);
    }
    @Test void disabledUploadChecksOwnershipAndDoesNotRequireStorageCredentials() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.upload(1, 2, java.util.UUID.randomUUID().toString(), file()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        verify(projects).requireOwnedActiveProject(1L, 2L); verifyNoInteractions(storage, validator, transactions, slots);
    }
    @Test void commitExceptionsHaveSafe503AndReleaseTheRequestScopedFile(CapturedOutput output) {
        var input = new ValidatedDocument(Path.of("synthetic"), "synthetic.txt", "txt", 1, "a".repeat(64), "b".repeat(64));
        var row = new DocumentRow(); row.id = 3L; row.putToken = java.util.UUID.randomUUID().toString();
        row.bucket = "synthetic-bucket"; row.objectKey = "synthetic-key"; row.byteSize = 1; row.sha256 = input.sha256();
        when(validator.prepare(any())).thenReturn(input); when(transactions.reserve(eq(1L), eq(2L), anyString(), eq(input))).thenReturn(new Reservation(row, true));
        when(transactions.startPut(3, 0, row.putToken)).thenReturn(row);
        when(transactions.finishUpload(3, row.putToken, true)).thenThrow(new TransactionSystemException("synthetic private database diagnostic"));
        assertThatThrownBy(() -> service.upload(1, 2, java.util.UUID.randomUUID().toString(), file()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503); assertThat(error.getClientMessage()).isEqualTo(ErrorCode.KNOWLEDGE_DATABASE_UNAVAILABLE.getMessage());
                    assertThat(error.getCause()).isNull();
                });
        verify(validator).release(input); verify(storage).put(row.location(), input.path(), 1, input.sha256(), row.putToken);
        assertThat(output.getAll()).contains("KNOWLEDGE_DATABASE_UNAVAILABLE")
                .doesNotContain("synthetic private database diagnostic", "synthetic.txt", row.bucket, row.objectKey, row.putToken, input.sha256());
    }
    private MockMultipartFile file() { return new MockMultipartFile("file", "synthetic.txt", "text/plain", new byte[]{'x'}); }
}
