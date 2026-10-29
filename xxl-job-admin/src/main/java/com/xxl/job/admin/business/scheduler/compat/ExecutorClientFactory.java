package com.xxl.job.admin.business.scheduler.compat;

import com.xxl.job.admin.business.mapper.XxlJobRegistryMapper;
import com.xxl.job.admin.business.model.XxlJobGroup;
import com.xxl.job.core.constant.Const;
import com.xxl.job.core.openapi.executor.ExecutorBiz;
import com.xxl.tool.http.HttpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 按注册地址选择协议。缓存容量阈值为 1000，最多延迟 5 秒看到其他实例识别的协议变化。
 */
@Component
public class ExecutorClientFactory implements InitializingBean {
    private static final Logger logger = LoggerFactory.getLogger(ExecutorClientFactory.class);
    private static final long CACHE_TTL = TimeUnit.SECONDS.toNanos(5);
    private final ConcurrentMap<String, CachedClient> clients = new ConcurrentHashMap<>();
    private final XxlJobRegistryMapper registry;
    private final LegacyExecutorSupport legacy;
    private final int timeout;

    public ExecutorClientFactory(XxlJobRegistryMapper registry, LegacyExecutorSupport legacy,
                                 @Value("${xxl.job.timeout}") int timeout) {
        this.registry = registry;
        this.legacy = legacy;
        this.timeout = timeout;
    }

    @Override
    public void afterPropertiesSet() {
        // 在启动调度前验证新增列，避免遗漏迁移后才在后台心跳或任务执行时失败。
        try {
            registry.findExecutorProtocol("__schema_probe__", "", "");
        } catch (RuntimeException e) {
            throw new IllegalStateException("Executor protocol schema unavailable; apply 20261029_legacy_executor_protocol.sql before startup", e);
        }
    }

    public ExecutorBiz get(String address, XxlJobGroup group) {
        if (address == null || address.isBlank() || group == null) return null;
        address = address.trim();
        String key = address + "|" + group.getAppname() + "|" + group.getAccessToken();
        CachedClient cached = clients.get(key);
        long now = System.nanoTime();
        if (cached != null && now - cached.createdAt() < CACHE_TTL) return cached.client();

        String alternative = address.endsWith("/") ? address.substring(0, address.length() - 1) : address + "/";
        String storedProtocol = registry.findExecutorProtocol(group.getAppname(), address, alternative);
        ExecutorProtocol protocol = storedProtocol == null ? ExecutorProtocol.CURRENT : ExecutorProtocol.valueOf(storedProtocol);
        ExecutorBiz client;
        if (protocol == ExecutorProtocol.V3_4_0) {
            if (!legacy.isEnabled()) throw new IllegalStateException("XXL-JOB 3.4.0 compatibility is disabled");
            String token = legacy.tokenFor(group);
            if (token == null || token.isBlank()) throw new IllegalStateException("Legacy executor token is missing");
            LegacyExecutorBiz delegate = HttpTool.createClient().url(address)
                    .connectTimeout(timeout * 1000).readTimeout(timeout * 1000)
                    .header(Const.XXL_JOB_ACCESS_TOKEN, token).proxy(LegacyExecutorBiz.class);
            client = new LegacyExecutorAdapter(delegate);
        } else {
            client = HttpTool.createClient().url(address)
                    .connectTimeout(timeout * 1000).readTimeout(timeout * 1000)
                    .header(Const.XXL_JOB_ACCESS_TOKEN, group.getAccessToken())
                    .header(Const.XXL_JOB_APPNAME, group.getAppname()).proxy(ExecutorBiz.class);
        }
        if (clients.size() >= 1000) clients.clear();
        clients.put(key, new CachedClient(client, now));
        logger.debug("创建执行器客户端，appname={}，address={}，protocol={}", group.getAppname(), address, protocol);
        return client;
    }

    private record CachedClient(ExecutorBiz client, long createdAt) { }
}
