package io.github.finalpi.compat;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 在独立 JVM 中运行 Maven Central 发布的 3.4.0 SDK，不加载当前 SDK 的任何类。
 */
public final class Legacy340ExecutorMain {
    public static void main(String[] args) throws Exception {
        String sdk = XxlJobExecutor.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!sdk.endsWith("xxl-job-core-3.4.0.jar")) throw new IllegalStateException("Unexpected SDK: " + sdk);
        System.out.println("SDK=3.4.0 " + sdk);
        XxlJobExecutor executor = new XxlJobExecutor();
        executor.setAdminAddresses(args[0]);
        executor.setAppname(args[1]);
        executor.setAccessToken(args[2]);
        executor.setIp("127.0.0.1");
        executor.setPort(Integer.parseInt(args[3]));
        executor.setLogPath(args[4]);
        XxlJobExecutor.registryJobHandler("legacyProbe", new IJobHandler() {
            @Override public void execute() throws Exception {
                XxlJobHelper.log("LEGACY_340_PROBE " + XxlJobHelper.getJobParam());
                if ("fail".equals(XxlJobHelper.getJobParam())) XxlJobHelper.handleFail("LEGACY_340_EXPECTED_FAILURE");
                else if ("sleep".equals(XxlJobHelper.getJobParam())) Thread.sleep(60000);
                else XxlJobHelper.handleSuccess("LEGACY_340_SUCCESS");
            }
        });
        executor.start();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (input.readLine() != null) break;
        } finally {
            executor.destroy();
        }
        // 旧 SDK 的 HTTP 工作线程有 60 秒空闲存活期；测试进程在注销完成后主动退出。
        System.exit(0);
    }
}
