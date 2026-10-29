package com.xxl.job.admin;

import com.xxl.job.admin.business.mapper.*;
import com.xxl.job.admin.business.model.*;
import com.xxl.job.admin.business.scheduler.LogPartitionManager;
import com.xxl.job.admin.framework.mapper.XxlJobUserMapper;
import com.xxl.job.admin.framework.model.XxlJobUser;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 PostgreSQL 验证方言、主键回填、分区回收及并发边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresIntegrationTest {
    @Resource DataSource dataSource;
    @Resource JdbcTemplate jdbc;
    @Resource XxlJobUserMapper users;
    @Resource XxlJobInfoMapper jobs;
    @Resource XxlJobGroupMapper groups;
    @Resource XxlJobLogMapper logs;
    @Resource XxlJobLogReportMapper reports;
    @Resource XxlJobLogGlueMapper glues;
    @Resource LogPartitionManager partitions;

    @Test
    @Transactional
    void userCrudGeneratedKeysAndPagination() {
        XxlJobUser user = new XxlJobUser();
        user.setUsername("postgres-test");
        user.setPassword("test-only");
        user.setRole(0);
        user.setPermission("1");
        assertEquals(1, users.save(user));
        assertTrue(user.getId() > 1);
        assertEquals(user.getId(), users.loadByUserName(user.getUsername()).getId());
        assertEquals(1, users.pageListCount(0, 10, "postgres-test", 0));
        assertEquals(1, users.pageList(0, 1, "postgres-test", 0).size());
        assertTrue(users.pageList(1, 1, "postgres-test", 0).isEmpty());
        user.setRole(1);
        assertEquals(1, users.update(user));
        assertEquals(1, users.updateToken(user.getId(), "test-token"));
        assertEquals("test-token", users.loadById(user.getId()).getToken());
        assertEquals(1, users.delete(user.getId()));
        assertNull(users.loadById(user.getId()));
    }

    @Test
    @Transactional
    void groupFiltersAndJobScheduleBatchPreserveStoppedState() {
        assertEquals(2, groups.pageListCount(0, 10, null, null));
        assertEquals(1, groups.pageList(1, 1, null, null).size());
        assertEquals(1, groups.pageList(0, 10, "sample-ai", null).size());
        XxlJobInfo job = jobs.loadById(1);
        job.setId(0);
        job.setName("postgres-test");
        job.setTriggerStatus(1);
        job.setTriggerNextTime(Long.MAX_VALUE - 1);
        assertEquals(1, jobs.save(job));
        assertTrue(job.getId() > 4);
        assertEquals(1, jobs.pageList(0, 10, 1, 1, "postgres", "demo", "XXL").size());
        job.setTriggerNextTime(1234);
        assertEquals(1, jobs.scheduleBatchUpdate(List.of(job)));
        assertEquals(1234, jobs.loadById(job.getId()).getTriggerNextTime());
        assertTrue(jobs.scheduleJobQuery(1234, 10).stream().anyMatch(j -> j.getId() == job.getId()));
        job.setTriggerStatus(0);
        assertEquals(1, jobs.update(job));
        job.setTriggerStatus(1);
        assertEquals(0, jobs.scheduleBatchUpdate(List.of(job)));
        assertEquals(0, jobs.scheduleUpdate(job));
        assertEquals(0, jobs.loadById(job.getId()).getTriggerStatus());
        assertEquals(1, jobs.delete(job.getId()));
    }

    private XxlJobLog newLog(int code) {
        XxlJobLog log = new XxlJobLog();
        log.setJobGroup(1);
        log.setJobId(987654);
        log.setTriggerTime(new java.util.Date());
        log.setTriggerCode(200);
        log.setHandleCode(code);
        assertEquals(1, logs.save(log));
        assertTrue(log.getId() > 0);
        return log;
    }

    @Test
    @Transactional
    void logLifecycleMapKeysFiltersAlarmAndManualCleanup() {
        XxlJobLog success = newLog(200);
        XxlJobLog failed = newLog(500);
        XxlJobLog running = newLog(0);
        running.setExecutorAddress("http://127.0.0.1:1");
        assertEquals(1, logs.updateTriggerInfo(running));
        assertEquals(1, logs.pageList(0, 10, 1, 987654, null, null, 1).size());
        assertEquals(1, logs.pageListCount(0, 10, 1, 987654, null, null, 2));
        assertEquals(1, logs.pageListCount(0, 10, 1, 987654, null, null, 3));
        assertEquals(1, logs.pageList(1, 1, 1, 987654, new java.util.Date(0), new java.util.Date(), 0).size());
        Map<String, Object> count = logs.findLogReport(new java.util.Date(0), new java.util.Date());
        assertTrue(count.containsKey("triggerDayCount"));
        assertTrue(((Number) count.get("triggerDayCountSuc")).longValue() >= 1);
        assertTrue(logs.findFailJobLogIds(100).contains(failed.getId()));
        assertTrue(logs.findLostJobIds(new java.util.Date()).contains(running.getId()));
        assertEquals(1, logs.updateAlarmStatus(failed.getId(), 0, 2));
        assertEquals(0, logs.updateAlarmStatus(failed.getId(), 0, 2));
        running.setHandleCode(200);
        running.setHandleTime(new java.util.Date());
        running.setHandleMsg("完成");
        assertEquals(1, logs.updateHandleInfo(running));
        assertEquals(1, logs.load(running.getId()).getAlarmStatus());
        List<Long> old = logs.findClearLogIds(1, 987654, new java.util.Date(), 1, 100);
        assertEquals(2, old.size());
        assertEquals(2, logs.clearLog(old));
        assertEquals(1, logs.delete(987654));
        assertNull(logs.load(success.getId()));
    }

    @Test
    @Transactional
    void reportUpsertSkipsUnchangedRowsAndGlueRetention() {
        XxlJobLogReport report = new XxlJobLogReport();
        report.setTriggerDay(Date.valueOf("2000-01-01"));
        report.setSucCount(42);
        report.setUpdateTime(new java.util.Date());
        assertEquals(1, reports.saveOrUpdate(report));
        assertTrue(report.getId() > 0);
        assertEquals(0, reports.saveOrUpdate(report));
        report.setSucCount(43);
        assertEquals(1, reports.saveOrUpdate(report));
        assertEquals(43, reports.queryLogReport(report.getTriggerDay(), report.getTriggerDay()).get(0).getSucCount());
        assertTrue(reports.queryLogReportTotal().getSucCount() >= 43);
        for (int i = 0; i < 3; i++) {
            XxlJobLogGlue glue = new XxlJobLogGlue();
            glue.setJobId(987654);
            glue.setGlueRemark("测试版本");
            glue.setUpdateTime(new java.util.Date(i * 1000L));
            assertEquals(1, glues.save(glue));
            assertTrue(glue.getId() > 0);
        }
        assertEquals(2, glues.removeOld(987654, 1));
        assertEquals(1, glues.findByJobId(987654).size());
        assertEquals(1, glues.deleteByJobId(987654));
    }

    @Test
    void droppingExpiredPartitionReclaimsFilesWithoutDeleteDeadTuples() {
        LocalDate day = LocalDate.now().minusDays(60);
        String table = "xxl_job_log_p" + day.toString().replace("-", "");
        jdbc.queryForObject("SELECT xxl_job_maintain_log_partitions(-1, ?)", Integer.class, Date.valueOf(day));
        jdbc.update("INSERT INTO xxl_job_log(job_group, job_id, trigger_time, trigger_code, handle_code) "
                + "SELECT 1, 987654, ?::timestamp, 200, 200 FROM generate_series(1, 100000)", Date.valueOf(day));
        long bytes = jdbc.queryForObject("SELECT pg_total_relation_size(?::regclass)", Long.class, table);
        assertTrue(bytes > 1_000_000);
        int liveBefore = jdbc.queryForObject("SELECT count(*) FROM xxl_job_log WHERE trigger_time >= ?", Integer.class,
                Date.valueOf(LocalDate.now().minusDays(3)));
        assertTrue(partitions.maintain(30) > 0);
        assertNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM xxl_job_log WHERE trigger_time = ?", Integer.class, Date.valueOf(day)));
        assertEquals(liveBefore, jdbc.queryForObject("SELECT count(*) FROM xxl_job_log WHERE trigger_time >= ?", Integer.class,
                Date.valueOf(LocalDate.now().minusDays(3))));
        assertEquals(0L, jdbc.queryForObject("SELECT coalesce(sum(n_tup_del), 0)::bigint FROM pg_stat_user_tables "
                + "WHERE relname LIKE 'xxl_job_log_p%' AND relname < ?", Long.class,
                "xxl_job_log_p" + LocalDate.now().minusDays(30).toString().replace("-", "")));
    }

    @Test
    void retentionBoundaryDisabledCleanupAndFuturePartition() {
        LocalDate today = LocalDate.now();
        LocalDate old = today.minusDays(31);
        jdbc.queryForObject("SELECT xxl_job_maintain_log_partitions(-1, ?)", Integer.class, Date.valueOf(old));
        String oldTable = "xxl_job_log_p" + old.toString().replace("-", "");
        assertEquals(0, partitions.maintain(-1));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, oldTable));
        assertThrows(Exception.class, () -> partitions.maintain(1));
        partitions.maintain(30);
        assertNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, oldTable));
        String boundary = "xxl_job_log_p" + today.minusDays(30).toString().replace("-", "");
        assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, boundary));
        String future = "xxl_job_log_p" + today.plusDays(7).toString().replace("-", "");
        assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, future));
    }

    @Test
    void maintenanceLockIsSharedAndTransactionScoped() throws Exception {
        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            first.createStatement().execute("SELECT pg_advisory_xact_lock(hashtext(current_database()), hashtext(current_schema() || '.xxl_job_log'))");
            assertEquals(-1, partitions.maintain(30));
            first.rollback();
        }
        assertEquals(0, partitions.maintain(30));
    }

    @Test
    void retentionUsesCatalogBoundsRatherThanPartitionName() {
        LocalDate future = LocalDate.now().plusDays(50);
        jdbc.execute("CREATE TABLE xxl_job_log_p19000101 PARTITION OF xxl_job_log FOR VALUES FROM ('"
                + future + "') TO ('" + future.plusDays(1) + "')");
        try {
            partitions.maintain(30);
            assertNotNull(jdbc.queryForObject("SELECT to_regclass('xxl_job_log_p19000101')::text", String.class));
        } finally {
            jdbc.execute("DROP TABLE xxl_job_log_p19000101");
        }
    }

    @Test
    void lockTimeoutPreservesPartitionAndAllowsRetry() throws Exception {
        LocalDate old = LocalDate.now().minusDays(70);
        jdbc.queryForObject("SELECT xxl_job_maintain_log_partitions(-1, ?)", Integer.class, Date.valueOf(old));
        String table = "xxl_job_log_p" + old.toString().replace("-", "");
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("LOCK TABLE ONLY " + table + " IN ACCESS SHARE MODE");
            long started = System.nanoTime();
            assertThrows(Exception.class, () -> partitions.maintain(30));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10);
            assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table));
            blocker.rollback();
        }
        assertTrue(partitions.maintain(30) > 0);
        assertNull(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table));
    }

    @Test
    @Transactional
    void emptyReportHasZeroCountsAndStableAliases() {
        var report = logs.findLogReport(Date.valueOf("1990-01-01"), Date.valueOf("1990-01-02"));
        for (String key : List.of("triggerDayCount", "triggerDayCountRunning", "triggerDayCountSuc")) {
            assertEquals(0, ((Number) report.get(key)).longValue());
        }
    }

    @Test
    void concurrentMaintenanceAndLogWritesKeepIdsUnique() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) tasks.add(() -> newLog(200).getId());
            tasks.add(() -> (long) partitions.maintain(30));
            Set<Long> ids = new HashSet<>();
            List<Future<Long>> results = pool.invokeAll(tasks, 20, TimeUnit.SECONDS);
            for (int i = 0; i < 40; i++) assertTrue(ids.add(results.get(i).get()));
            results.get(40).get();
            assertEquals(40, ids.size());
        } finally {
            pool.shutdownNow();
            logs.delete(987654);
        }
    }
}
