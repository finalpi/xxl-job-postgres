package com.xxl.job.admin.business.scheduler.compat;

import com.xxl.job.core.openapi.executor.ExecutorBiz;
import com.xxl.job.core.openapi.executor.dto.*;
import com.xxl.tool.response.Response;

/**
 * 将当前调度接口转换为旧协议；不在失败后重发任务，避免重复执行。
 */
public final class LegacyExecutorAdapter implements ExecutorBiz {
    private final LegacyExecutorBiz delegate;

    public LegacyExecutorAdapter(LegacyExecutorBiz delegate) {
        this.delegate = delegate;
    }

    @Override public Response<String> beat() { return delegate.beat(); }
    @Override public Response<String> idleBeat(IdleBeatRequest request) { return delegate.idleBeat(request); }
    @Override public Response<String> trigger(TriggerRequest request) { return delegate.run(request); }
    @Override public Response<String> kill(KillRequest request) { return delegate.kill(request); }
    @Override public Response<LogData> log(LogRequest request) {
        return delegate.log(new LegacyLogRequest(request.getLogId(), request.getLogDateTime(), request.getFromLineNum()));
    }
}
