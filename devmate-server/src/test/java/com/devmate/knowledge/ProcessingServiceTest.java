package com.devmate.knowledge;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.application.ProcessingService;
import com.devmate.knowledge.application.ProcessingTransactions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessingServiceTest {
    @Test void invalidUuidNeverCallsTheTransactionAndCanonicalUuidRetainsItsIdentity() {
        var transactions=mock(ProcessingTransactions.class); var service=new ProcessingService(transactions);
        for (String value : new String[]{null,"invalid","1-1-1-1-1"," "})
            assertThatThrownBy(() -> service.start(1,2,3,value)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transactions);
        String uuid=UUID.randomUUID().toString(); service.start(1,2,3,uuid.toUpperCase());
        verify(transactions).start(1,2,3,uuid);
    }
    @Test void databaseAndUncertainCommitFailuresBecomeSafe503WithoutInfrastructureCause() {
        var transactions=mock(ProcessingTransactions.class); var service=new ProcessingService(transactions); String uuid=UUID.randomUUID().toString();
        when(transactions.start(1,2,3,uuid)).thenThrow(new TransactionSystemException("private commit diagnostic"));
        when(transactions.status(1,2,3)).thenThrow(new DataAccessResourceFailureException("private database diagnostic"));
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable action : java.util.List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> service.start(1,2,3,uuid), () -> service.status(1,2,3)))
            assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,error -> {
                assertThat(error.getClientMessage()).isEqualTo(ErrorCode.KNOWLEDGE_DATABASE_UNAVAILABLE.getMessage());
                assertThat(error.getHttpStatus().value()).isEqualTo(503); assertThat(error.getCause()).isNull();
            });
    }
}
