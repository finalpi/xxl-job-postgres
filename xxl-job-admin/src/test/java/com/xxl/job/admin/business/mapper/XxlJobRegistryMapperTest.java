package com.xxl.job.admin.business.mapper;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class XxlJobRegistryMapperTest {
    @Resource private XxlJobRegistryMapper registry;

    @Test
    void concurrentUpsertAndExpiry() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        String key = "test-" + UUID.randomUUID();
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i=0; i<64; i++) {
                work.add(() -> registry.registrySaveOrUpdate("EXECUTOR", key, "http://127.0.0.1:1", new Date()));
            }
            for (Future<Integer> result : pool.invokeAll(work)) assertEquals(1, result.get());
            assertEquals(1, registry.findAll(90, new Date()).stream().filter(r -> key.equals(r.getRegistryKey())).count());
            registry.registrySaveOrUpdate("EXECUTOR", key, "http://127.0.0.1:1", new Date(0));
            var dead = registry.findDead(90, new Date());
            assertFalse(dead.isEmpty());
            assertEquals(1, registry.removeByRegistryGroupAndKey("EXECUTOR", key));
        } finally {
            registry.removeByRegistryGroupAndKey("EXECUTOR", key);
            pool.shutdownNow();
        }
    }
}
