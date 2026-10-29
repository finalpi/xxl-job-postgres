package com.xxl.job.admin.business.scheduler.compat;

import com.xxl.job.core.openapi.executor.dto.*;
import com.xxl.tool.response.Response;

/**
 * 3.4.0 执行器 HTTP 协议，仅供调度中心适配旧执行器。
 */
public interface LegacyExecutorBiz {
    Response<String> beat();
    Response<String> idleBeat(IdleBeatRequest request);
    Response<String> run(TriggerRequest request);
    Response<String> kill(KillRequest request);
    Response<LogData> log(LegacyLogRequest request);
}
