# PostgreSQL 版本使用说明

本 fork 基于 XXL-JOB `3.5.1-SNAPSHOT`，调度中心使用 PostgreSQL 14 及以上版本，保留任务 ID 类型，并通过兼容层支持原版 3.4.0 执行器。此分支的 SQL 不再用于 MySQL。旧协议、令牌和升级要求见 [3.4.0 兼容说明](COMPATIBILITY_3_4_0.md)。

## 启动

准备一个空数据库 `xxl_job`，依次执行：

```sh
psql -d xxl_job -v ON_ERROR_STOP=1 -f doc/db/tables_xxl_job.sql
psql -d xxl_job -v ON_ERROR_STOP=1 -f doc/db/log_partitions.sql
```

两个脚本可在维护窗口重复执行，不会覆盖已有用户、任务和执行器。身份序列不会因重复初始化而倒退。数据库账号需要拥有这些表和函数，并具有所在 schema 的建表权限；分区维护以应用账号身份运行，不使用 `SECURITY DEFINER`，不需要超级用户。

Docker Compose 会在空数据卷首次启动时按同一顺序初始化：

```sh
export POSTGRES_PASSWORD='请替换为数据库密码'
docker compose -f docker/docker-compose.yml up -d
```

访问 `http://localhost:8080`。沿用上游初始账号 `admin` / `123456`，首次使用后修改密码和示例执行器的 `default_token`。已有数据卷不会自动执行初始化脚本。不能把原 MySQL 数据卷挂给 PostgreSQL，也不能将已有普通 PostgreSQL 日志表直接当作分区表使用。

发布镜像：`ghcr.io/finalpi/xxl-job-postgres:latest`，同一个标签包含 `linux/amd64` 和 `linux/arm64`。只有默认分支测试和发布成功后才生成或更新 `latest`；分支构建使用分支标签和 `sha-完整提交号`。镜像以 UID/GID `10001:10001` 运行，绑定挂载的日志目录需允许该账号写入。

若本地没有已发布镜像，可以从源码构建：

```sh
docker compose -f docker/docker-compose.yml up -d --build
```

常用环境变量：

| 变量 | 默认值或要求 | 含义 |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://127.0.0.1:5432/xxl_job` | PostgreSQL 连接地址 |
| `SPRING_DATASOURCE_USERNAME` | `xxl_job` | 数据库账号 |
| `SPRING_DATASOURCE_PASSWORD` | 无生产默认密码 | 数据库密码；Compose 要求 `POSTGRES_PASSWORD` 非空 |
| `XXL_JOB_LOGRETENTIONDAYS` | `30` | 日志保留天数；至少 3 天，小于 3 禁用自动删除 |
| `JAVA_OPTS` | 镜像默认为空 | JVM 参数，位于 `-jar` 前；Compose 默认 `-Xms128m -Xmx512m` |
| `TZ` | 镜像和 Compose 为 `Asia/Shanghai` | 调度日界线；Java 和数据库应使用一致时区 |

`/actuator/health` 允许无登录读取健康状态。应用启动前检查分区函数并补齐日分区；数据库、初始化脚本或权限缺失会使启动失败，避免带着不可写的日志表开始调度。

## 日志清理和空间回收

`xxl_job_log` 按 `trigger_time` 每天一个范围分区。共享身份序列生成日志 ID，数据库主键为 `(id, trigger_time)`，符合 PostgreSQL 分区唯一约束要求。应用仍按原有 long ID 读取和回调；不要手工导入跨日期重复 ID。只按 ID 查询会访问各保留分区的 ID 索引，保留期很长时需要评估分区数量和查询耗时。

维护行为：

- 启动时补齐最近 3 天至未来 7 天的分区。运行时每小时维护一次；报表汇总失败不会阻断分区维护。
- 多个调度中心通过 PostgreSQL 事务级 advisory lock 协调。已有实例维护时，其余实例立即跳过，下一轮重试。
- 只删除实际挂在日志父表上、符合本项目命名规则、实际分区上界不晚于过期日零点的分区。保留期按整日计算，边界当天仍保留。
- 每次最多删除 32 个分区，DDL 锁等待上限 2 秒，JDBC 查询超时 15 秒。失败会回滚本次维护，下一分钟重试。大量历史分区会分轮回收。
- 自动到期清理使用 `DROP TABLE` 删除整个子表，释放该分区的数据和索引文件，不逐行删除过期日志。此机制来自 [PostgreSQL 分区维护方式](https://www.postgresql.org/docs/current/ddl-partitioning.html)。

这不保证数据库零死元组。任务状态、回调、告警、心跳更新，以及页面手工删除日志仍遵循 PostgreSQL MVCC。新建日志分区设置 `fillfactor=80`、`autovacuum_vacuum_scale_factor=0.02`、`autovacuum_vacuum_threshold=1000`、`autovacuum_analyze_scale_factor=0.05`；心跳和日报表的回收阈值为 100。无变化的日报表不再每分钟执行无效更新。

必须保持数据库 autovacuum 开启。长事务、复制槽及 vacuum 资源限制仍可能推迟回收，参见 [PostgreSQL 日常清理说明](https://www.postgresql.org/docs/current/routine-vacuuming.html)。不在定时任务中执行会重写整表的 `VACUUM FULL`。初始化参数只作用于本 fork 创建的表；修改配置脚本不会自动更新已存在分区的存储参数。

没有无限增长的默认分区。后台维护故障持续超过预建窗口时，写入未来无分区的日期会失败并记录错误，应修复数据库权限或锁阻塞后重启应用补齐分区。自动清理也会移除超过保留期的未结束任务日志，因此保留期应大于最长任务运行和回调重试周期。任务日报表是独立的小型汇总表，按上游行为保留，不随明细分区删除。

检查回收情况：

```sql
SELECT relname, n_live_tup, n_dead_tup, last_autovacuum,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_stat_user_tables
WHERE relname LIKE 'xxl_job_log_p%'
ORDER BY relname;
```

`n_dead_tup` 是估算值。到期分区已被删除、对应关系和索引文件消失，是自动清理的主要验证依据。

## 测试和镜像发布

测试数据库必须专用；测试会创建和删除分区并写入 10 万条压力数据，不能指向生产数据库。初始化后设置：

```sh
export TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/xxl_job
export TEST_DATABASE_USER=xxl_job
export TEST_DATABASE_PASSWORD='数据库密码'
mvn -B -ntp -pl xxl-job-admin -am -Dmaven.javadoc.skip=true -Dgpg.skip=true verify
mvn -B -ntp -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true package
```

第一条运行 core 和 admin 的自动测试，覆盖登录、Mapper 方言、分页、生成主键、报表别名及 UPSERT、并发心跳、调度锁、保留期边界、锁超时重试、分区回收、并发写入，以及真实执行器的 Cron、手工触发、成功/失败回调落库。第二条编译全部示例模块。上游 `openapi` 包内的手工示例测试需要外部固定地址服务，未纳入默认测试；AI 示例需要外部模型或 API，不作为自动验收依据。端到端测试自行启动真实执行器，无需预先运行外部执行服务。

GitHub Actions 对 PostgreSQL 14、16、18 和原生 amd64、arm64 runner 组成的 6 种组合执行上述测试、初始化重放和容器健康检查。全部通过后，用经过测试的同一份 Jar 构建两种架构并推送 GHCR，再校验 manifest。PR 只测试；分支、默认分支和版本标签按工作流发布镜像。发布只使用仓库自带 `GITHUB_TOKEN`，需要 Actions 的 `packages: write` 权限。

## 已有数据库迁移与回滚

本次提供新建 PostgreSQL 部署，不自动迁移 MySQL 数据或已有非分区 PostgreSQL 表。已有数据迁移需要单独停写、备份、字段转换、按日期导入、校准所有身份序列，并验证任务数量、状态、日志和权限后切换。不能只替换数据库驱动。

切换前保留原数据库和原镜像；需要回滚时，停止新调度中心并恢复原配置、镜像和数据库，避免两个数据库同时调度同一批任务。过期分区删除无法通过回滚代码恢复，恢复历史明细必须使用备份。
