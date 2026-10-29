package com.xxl.job.admin.business.schedule;

import com.xxl.job.admin.business.mapper.XxlJobLockMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class JobScheduleTest {
    @Resource private XxlJobLockMapper locks;
    @Resource private PlatformTransactionManager transactionManager;

    @Test
    void scheduleLockSerializesTransactionsAndIsReleased() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Future<?>[] waiter = new Future<?>[1];
            tx.executeWithoutResult(status -> {
                assertEquals("schedule_lock", locks.scheduleLock());
                waiter[0] = pool.submit(() -> {
                    started.countDown();
                    tx.executeWithoutResult(other -> assertEquals("schedule_lock", locks.scheduleLock()));
                });
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> waiter[0].get(150, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            waiter[0].get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
}
