package com.xxl.job.admin.business.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;

/**
 * 维护 PostgreSQL 日志日分区，避免自动清理通过批量删除制造大量死元组。
 */
@Component
public class LogPartitionManager implements InitializingBean {
    private static final Logger logger = LoggerFactory.getLogger(LogPartitionManager.class);
    private final JdbcTemplate jdbcTemplate;

    public LogPartitionManager(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(15);
    }

    @Override
    public void afterPropertiesSet() {
        // 启动调度前补齐分区；保留期清理由报表线程执行，避免先删除待汇总数据。
        maintain(-1);
    }

    /**
     * 每次最多回收 32 个过期分区；负数表示只建分区、不删除。
     */
    public int maintain(int retentionDays) {
        Integer dropped = jdbcTemplate.queryForObject(
                "SELECT xxl_job_maintain_log_partitions(?, ?)", Integer.class,
                retentionDays, Date.valueOf(LocalDate.now()));
        int result = dropped == null ? 0 : dropped;
        if (result >= 0) {
            logger.info("日志分区维护完成，retentionDays={}，droppedPartitions={}", retentionDays, result);
        }
        return result;
    }
}
