-- 已安装此 PostgreSQL fork 的数据库需在更新镜像前执行，可重复运行。
BEGIN;
ALTER TABLE xxl_job_registry
    ADD COLUMN IF NOT EXISTS executor_protocol VARCHAR(16) NOT NULL DEFAULT 'CURRENT';
COMMENT ON COLUMN xxl_job_registry.executor_protocol IS '执行器通信协议：CURRENT 或 V3_4_0，由已鉴权心跳确定';
COMMIT;
