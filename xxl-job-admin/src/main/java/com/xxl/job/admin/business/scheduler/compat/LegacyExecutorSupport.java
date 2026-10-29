package com.xxl.job.admin.business.scheduler.compat;

import com.xxl.job.admin.business.mapper.XxlJobGroupMapper;
import com.xxl.job.admin.business.mapper.XxlJobLogMapper;
import com.xxl.job.admin.business.model.XxlJobGroup;
import com.xxl.job.core.openapi.admin.dto.CallbackData;
import com.xxl.job.core.openapi.admin.dto.RegistryRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 为旧协议解析执行器身份；全局旧令牌只供未配置组令牌的执行器组使用。
 */
@Component
public class LegacyExecutorSupport {
    private final XxlJobGroupMapper groups;
    private final XxlJobLogMapper logs;
    private final boolean enabled;
    private final String oldGlobalToken;

    public LegacyExecutorSupport(XxlJobGroupMapper groups, XxlJobLogMapper logs,
                                 @Value("${xxl.job.compatibility.v340.enabled:true}") boolean enabled,
                                 @Value("${xxl.job.accessToken:}") String oldGlobalToken) {
        this.groups = groups;
        this.logs = logs;
        this.enabled = enabled;
        this.oldGlobalToken = oldGlobalToken;
    }

    public boolean isEnabled() { return enabled; }

    public String tokenFor(XxlJobGroup group) {
        return group.getAccessToken() == null || group.getAccessToken().isBlank()
                ? oldGlobalToken : group.getAccessToken();
    }

    public boolean authorizeRegistry(String token, RegistryRequest request) {
        if (!enabled || token == null || token.isBlank() || request == null
                || !"EXECUTOR".equals(request.getRegistryGroup())
                || request.getRegistryKey() == null || request.getRegistryKey().isBlank()
                || request.getRegistryValue() == null || request.getRegistryValue().isBlank()) return false;
        XxlJobGroup group = groups.loadByAppname(request.getRegistryKey());
        return group != null && matches(token, tokenFor(group));
    }

    public boolean authorizeCallbacks(String token, String appname, List<CallbackData> callbacks) {
        boolean legacy = appname == null;
        if ((legacy && !enabled) || token == null || token.isBlank() || callbacks == null || callbacks.isEmpty()) return false;
        if (callbacks.stream().anyMatch(c -> c == null || c.getLogId() <= 0 || c.getHandleCode() <= 0)) return false;
        List<Long> ids = callbacks.stream().map(CallbackData::getLogId).distinct().toList();
        // 旧 SDK 会一次性清空回调队列，按 500 个 ID 分段鉴权，不限制整批的历史兼容格式。
        for (int from = 0; from < ids.size(); from += 500) {
            if (logs.hasUnauthorizedCallbacks(ids.subList(from, Math.min(from + 500, ids.size())),
                    token, appname, legacy ? oldGlobalToken : null)) return false;
        }
        return true;
    }

    private boolean matches(String supplied, String expected) {
        return expected != null && !expected.isBlank() && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
