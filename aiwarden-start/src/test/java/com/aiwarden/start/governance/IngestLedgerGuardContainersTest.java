package com.aiwarden.start.governance;

import com.aiwarden.governance.ingest.IngestLedgerGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摄入账本仲裁语义（FR-ING-01）：在真实 PostgreSQL 上验证唯一键抢占的状态机。
 *
 * <p>并发 16 线程抢占同一 (docId, version) 恰好一个赢家——M1 验收②「16 线程并发投递只产生
 * 一份」的仲裁层先行验证（端到端版本随切片③的向量落库补齐）。
 */
@Testcontainers
@SpringBootTest(properties = "spring.flyway.enabled=true")
class IngestLedgerGuardContainersTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IngestLedgerGuard guard;

    @Test
    void concurrentClaims_exactlyOneWins() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return guard.tryClaimForIndex(42L, 2001L, 1);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void processingAndTerminalEntries_areNotReclaimable() {
        assertThat(guard.tryClaimForIndex(42L, 2002L, 1)).isTrue();

        assertThat(guard.tryClaimForIndex(42L, 2002L, 1)).isFalse();  // PROCESSING：并发重复
        guard.markIndexed(2002L, 1);
        assertThat(guard.tryClaimForIndex(42L, 2002L, 1)).isFalse();  // INDEXED：终态
    }

    @Test
    void deletedEntry_isTerminal() {
        assertThat(guard.tryClaimForIndex(42L, 2004L, 1)).isTrue();
        guard.markDeleted(2004L);

        assertThat(guard.tryClaimForIndex(42L, 2004L, 1)).isFalse();
    }

    @Test
    void delete_canReclaimIndexedEntry_butDeletedIsTerminal() {
        assertThat(guard.tryClaimForIndex(42L, 2005L, 1)).isTrue();
        guard.markIndexed(2005L, 1);

        // 删除是索引之后的正常状态流转：INDEXED 行可被删除抢占
        assertThat(guard.tryClaimForDelete(42L, 2005L, 1)).isTrue();
        guard.markDeleted(2005L);

        // DELETED 为文档级终态：重复删除投递被跳过
        assertThat(guard.tryClaimForDelete(42L, 2005L, 1)).isFalse();
    }

    @Test
    void failedEntry_canBeReclaimed_forKafkaRetryPath() {
        assertThat(guard.tryClaimForIndex(42L, 2003L, 1)).isTrue();
        guard.markFailed(2003L, 1, "模拟失败");

        assertThat(guard.tryClaimForIndex(42L, 2003L, 1)).isTrue();   // FAILED：Kafka 重试可重新抢占
    }
}
