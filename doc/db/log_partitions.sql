-- PostgreSQL 14+：先执行 tables_xxl_job.sql，再执行本文件。函数以调用者权限运行。
BEGIN;

CREATE OR REPLACE FUNCTION xxl_job_maintain_log_partitions(
    retention_days integer, maintenance_day date DEFAULT CURRENT_DATE
) RETURNS integer
LANGUAGE plpgsql
SET lock_timeout = '2s'
AS $$
DECLARE
    schema_name text := current_schema();
    partition_day date;
    partition_name text;
    partition_record record;
    upper_bound text[];
    dropped integer := 0;
BEGIN
    IF maintenance_day IS NULL OR retention_days IS NULL OR
       (retention_days >= 0 AND retention_days < 3) THEN
        RAISE EXCEPTION 'retention_days must be negative (disabled) or at least 3';
    END IF;

    -- 多实例共用事务锁；已有维护任务时立即返回，连接归还后不会遗留会话锁。
    IF NOT pg_try_advisory_xact_lock(hashtext(current_database()), hashtext(schema_name || '.xxl_job_log')) THEN
        RETURN -1;
    END IF;

    -- 只提前创建有限的日分区；不在每条日志写入的热路径执行 DDL。
    FOR day_offset IN -3..7 LOOP
        partition_day := maintenance_day + day_offset;
        partition_name := 'xxl_job_log_p' || to_char(partition_day, 'YYYYMMDD');
        IF to_regclass(format('%I.%I', schema_name, partition_name)) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I.%I PARTITION OF %I.xxl_job_log FOR VALUES FROM (%L) TO (%L)',
                schema_name, partition_name, schema_name, partition_day::timestamp, (partition_day + 1)::timestamp);
            EXECUTE format(
                'ALTER TABLE %I.%I SET (fillfactor=80, autovacuum_vacuum_scale_factor=0.02, '
                'autovacuum_vacuum_threshold=1000, autovacuum_analyze_scale_factor=0.05)',
                schema_name, partition_name);
        END IF;
    END LOOP;

    IF retention_days < 0 THEN
        RETURN 0;
    END IF;

    -- 仅处理真正挂在日志父表上的日分区，并以目录中的实际上界判断过期。
    FOR partition_record IN
        SELECT child.relname, pg_get_expr(child.relpartbound, child.oid) AS bounds
        FROM pg_inherits inheritance
        JOIN pg_class child ON child.oid = inheritance.inhrelid
        JOIN pg_namespace ns ON ns.oid = child.relnamespace
        WHERE inheritance.inhparent = to_regclass(format('%I.xxl_job_log', schema_name))
          AND ns.nspname = schema_name
          AND child.relname ~ '^xxl_job_log_p[0-9]{8}$'
        ORDER BY child.relname
    LOOP
        upper_bound := regexp_match(partition_record.bounds, 'TO \(''([^'']+)''\)');
        IF upper_bound IS NOT NULL AND upper_bound[1]::timestamp <= (maintenance_day - retention_days)::timestamp THEN
            -- 删除整张子表会释放数据和索引文件，不产生逐行 DELETE 的死元组。
            EXECUTE format('DROP TABLE %I.%I', schema_name, partition_record.relname);
            dropped := dropped + 1;
            EXIT WHEN dropped >= 32;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$;

SELECT xxl_job_maintain_log_partitions(-1);
COMMIT;
