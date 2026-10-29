package com.xxl.job.admin;

import com.xxl.job.admin.business.mapper.*;
import com.xxl.job.admin.business.model.XxlJobGroup;
import com.xxl.job.admin.business.scheduler.compat.*;
import com.xxl.job.core.openapi.admin.dto.RegistryRequest;
import com.xxl.job.core.openapi.executor.dto.*;
import com.xxl.tool.response.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyExecutorAdapterTest {
    @Test
    void rejectedTriggerIsNotRetriedAndLogDateFieldIsTranslated() {
        LegacyExecutorBiz delegate = mock(LegacyExecutorBiz.class);
        LegacyExecutorAdapter adapter = new LegacyExecutorAdapter(delegate);
        TriggerRequest trigger = new TriggerRequest();
        when(delegate.run(trigger)).thenReturn(Response.ofFail("expected failure"));
        assertFalse(adapter.trigger(trigger).isSuccess());
        verify(delegate, times(1)).run(trigger);
        adapter.log(new LogRequest(42, 1234567, 3));
        verify(delegate).log(new LegacyLogRequest(42, 1234567, 3));
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void disabledCompatibilityRejectsLegacyIdentityAndOutboundClient() {
        XxlJobGroupMapper groups = mock(XxlJobGroupMapper.class);
        XxlJobLogMapper logs = mock(XxlJobLogMapper.class);
        XxlJobRegistryMapper registry = mock(XxlJobRegistryMapper.class);
        LegacyExecutorSupport support = new LegacyExecutorSupport(groups, logs, false, "secret");
        assertFalse(support.authorizeRegistry("secret", new RegistryRequest("EXECUTOR", "app", "http://localhost:1")));
        assertFalse(support.authorizeCallbacks("secret", null, null));
        when(registry.findExecutorProtocol(anyString(), anyString(), anyString())).thenReturn("V3_4_0");
        XxlJobGroup group = new XxlJobGroup();
        group.setAppname("app");
        group.setAccessToken("secret");
        ExecutorClientFactory factory = new ExecutorClientFactory(registry, support, 1);
        assertThrows(IllegalStateException.class, () -> factory.get("http://localhost:1", group));
        verifyNoInteractions(groups, logs);
    }

    @Test
    void protocolCacheExpiresAndReloadsSharedDatabaseState() throws Exception {
        XxlJobRegistryMapper registry = mock(XxlJobRegistryMapper.class);
        LegacyExecutorSupport support = new LegacyExecutorSupport(mock(XxlJobGroupMapper.class), mock(XxlJobLogMapper.class), true, "");
        when(registry.findExecutorProtocol(anyString(), anyString(), anyString())).thenReturn("V3_4_0", "CURRENT");
        XxlJobGroup group = new XxlJobGroup();
        group.setAppname("app");
        group.setAccessToken("secret");
        ExecutorClientFactory factory = new ExecutorClientFactory(registry, support, 1);
        var old = factory.get("http://127.0.0.1:1", group);
        assertInstanceOf(LegacyExecutorAdapter.class, old);
        assertSame(old, factory.get("http://127.0.0.1:1", group));
        Thread.sleep(5100);
        assertFalse(factory.get("http://127.0.0.1:1", group) instanceof LegacyExecutorAdapter);
        verify(registry, times(2)).findExecutorProtocol(anyString(), anyString(), anyString());
    }
}
