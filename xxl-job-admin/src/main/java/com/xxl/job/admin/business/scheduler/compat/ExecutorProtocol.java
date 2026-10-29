package com.xxl.job.admin.business.scheduler.compat;

/**
 * 注册时识别的执行器协议，按执行器组和地址存入数据库，供各调度中心实例共享。
 */
public enum ExecutorProtocol {
    CURRENT,
    V3_4_0
}
