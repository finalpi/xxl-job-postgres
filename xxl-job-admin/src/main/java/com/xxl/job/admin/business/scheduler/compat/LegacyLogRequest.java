package com.xxl.job.admin.business.scheduler.compat;

/**
 * XXL-JOB 3.4.0 日志读取请求，保留旧协议的 logDateTim 拼写。
 * @param logId 日志 ID
 * @param logDateTim 原任务触发时间戳，单位毫秒
 * @param fromLineNum 起始行号
 */
public record LegacyLogRequest(long logId, long logDateTim, int fromLineNum) {
}
