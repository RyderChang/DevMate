package com.devmate.conversation;

import com.devmate.ai.application.*;
import com.devmate.common.exception.BusinessException;
import com.devmate.conversation.service.RagChatCalls;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RagChatCallsTest {
    @Test void nestedNetworkBudgetsCannotResetTheTotalAndRestoreTheOuterScope() {
        try(var outer=CallBudget.open(Duration.ofSeconds(1))) {
            assertThat(CallBudget.cap(Duration.ofSeconds(300))).isLessThanOrEqualTo(Duration.ofSeconds(1));
            try(var inner=CallBudget.open(Duration.ofNanos(1))) {
                assertThatThrownBy(()->CallBudget.cap(Duration.ofSeconds(10))).isInstanceOf(AiGatewayException.class);
            }
            assertThat(CallBudget.cap(Duration.ofSeconds(300))).isLessThanOrEqualTo(Duration.ofSeconds(1));
        }
        assertThat(CallBudget.cap(Duration.ofSeconds(300))).isEqualTo(Duration.ofSeconds(300));
    }
    @Test void concurrencyHasNoQueueAndARealWorkerMustExitBeforeSlotReuse() throws Exception {
        var calls=new RagChatCalls();var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        var finished=new CountDownLatch(4);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Future<?>>();
            for(int n=0;n<4;n++)tasks.add(executor.submit(()->assertThatThrownBy(()->calls.call(Duration.ofMillis(500),()-> {
                entered.countDown();
                try { while(release.getCount()>0)try { release.await(); } catch(InterruptedException ignored) { /* Simulate an uncancellable provider. */ } }
                finally { finished.countDown(); }
                return new AiChatResult("synthetic","late",1,1,2,1);
            })).isInstanceOf(BusinessException.class)));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                for(var task:tasks)task.get(5,TimeUnit.SECONDS);
                assertThatThrownBy(()->calls.call(Duration.ofSeconds(1),()->{throw new AssertionError("No queued dispatch");})).isInstanceOf(BusinessException.class);
            } finally { release.countDown(); }
            assertThat(finished.await(5,TimeUnit.SECONDS)).isTrue();
        }
    }
}
