package com.xxl.job.admin.business.scheduler.compat;

import com.google.gson.annotations.SerializedName;
import com.xxl.job.core.openapi.executor.dto.LogData;
import com.xxl.tool.response.Response;

/**
 * 兼容 2.4.0 的 content 与 3.4.0 的 data 日志响应字段。
 */
public final class LegacyLogResponse {
    /** 执行器返回码。 */
    private int code;
    /** 执行器返回说明。 */
    private String msg;
    /** 日志内容，两个已验证的旧协议使用不同封装字段。 */
    @SerializedName(value = "data", alternate = {"content"})
    private LogData data;

    public Response<LogData> toCurrent() { return Response.of(code, msg, data); }
}
