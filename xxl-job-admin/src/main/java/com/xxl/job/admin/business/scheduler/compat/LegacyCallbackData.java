package com.xxl.job.admin.business.scheduler.compat;

import com.xxl.job.core.openapi.admin.dto.CallbackData;

/**
 * 3.4.0 数组回调中的一条记录，保留旧时间字段，并转换为当前内部类型。
 * @param logId 日志 ID
 * @param logDateTim 触发时间戳，单位毫秒
 * @param handleCode 执行结果码
 * @param handleMsg 执行结果说明
 */
public record LegacyCallbackData(long logId, long logDateTim, int handleCode, String handleMsg) {
    public CallbackData toCurrent() {
        return new CallbackData(logId, logDateTim, handleCode, handleMsg);
    }
}
