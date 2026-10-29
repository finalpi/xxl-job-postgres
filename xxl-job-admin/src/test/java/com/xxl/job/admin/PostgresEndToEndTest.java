package com.xxl.job.admin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xxl.job.admin.business.mapper.*;
import com.xxl.job.admin.business.model.*;
import com.xxl.job.admin.business.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.job.core.constant.Const;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动真实执行器，通过 HTTP 创建和调度任务，验证执行回调持久化到 PostgreSQL。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresEndToEndTest {
    @Value("${local.server.port}") int adminPort;
    @Resource XxlJobInfoMapper jobs;
    @Resource XxlJobGroupMapper groups;
    @Resource XxlJobLogMapper logs;
    @Resource XxlJobRegistryMapper registry;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private JsonObject api(String method, String body, String token) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + adminPort + "/api/" + method))
                .timeout(Duration.ofSeconds(5))
                .header(Const.XXL_JOB_ACCESS_TOKEN, token)
                .header(Const.XXL_JOB_APPNAME, "xxl-job-executor-sample")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject api(String method, String body) throws Exception {
        JsonObject result = api(method, body, "default_token");
        assertEquals(200, result.get("code").getAsInt(), result.toString());
        return result;
    }

    private void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("等待真实调度和回调超时");
    }

    private boolean hasResult(int jobId, int code) {
        return logs.pageList(0, 100, 1, jobId, null, null, 0).stream()
                .anyMatch(log -> log.getHandleCode() == code);
    }

    @Test
    void cronManualTriggerSuccessFailureCallbackRegistryAndAuthentication(@TempDir Path logPath) throws Exception {
        XxlJobExecutor executor = new XxlJobExecutor();
        XxlJobGroup original = groups.load(1);
        int jobId = 0;
        try {
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + adminPort + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), health.body());
            assertEquals("UP", JsonParser.parseString(health.body()).getAsJsonObject().get("status").getAsString());
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            String address = "http://127.0.0.1:" + port + "/";
            executor.setAdminAddresses("http://127.0.0.1:" + adminPort);
            executor.setAppname("xxl-job-executor-sample");
            executor.setAccessToken("default_token");
            executor.setIp("127.0.0.1");
            executor.setPort(port);
            executor.setLogPath(logPath.toString());
            executor.registryJobHandler("postgresProbe", new IJobHandler() {
                @Override public void execute() {
                    XxlJobHelper.log("PostgreSQL 集成测试执行");
                    if ("fail".equals(XxlJobHelper.getJobParam())) XxlJobHelper.handleFail("预期失败");
                    else XxlJobHelper.handleSuccess("PostgreSQL 执行成功");
                }
            });
            executor.start();
            await(() -> registry.findAll(90, new java.util.Date()).stream()
                    .anyMatch(item -> address.equals(item.getRegistryValue())), 15);

            XxlJobGroup manual = groups.load(1);
            manual.setAddressType(1);
            manual.setAddressList(address);
            groups.update(manual);
            await(() -> address.equals(XxlJobAdminBootstrap.getInstance().getJobRegistryHelper()
                    .loadByAppName("xxl-job-executor-sample").getAddressList()), 40);

            assertNotEquals(200, api("addJob", "{}", "wrong-token").get("code").getAsInt());
            String request = """
                    {"jobGroup":1,"name":"PostgreSQL E2E","author":"test","scheduleType":"CRON",
                    "scheduleConf":"0/1 * * * * ?","misfireStrategy":"DO_NOTHING",
                    "executorRouteStrategy":"FIRST","executorHandler":"postgresProbe",
                    "executorBlockStrategy":"SERIAL_EXECUTION","glueType":"BEAN"}
                    """;
            jobId = api("addJob", request).get("data").getAsInt();
            int id = jobId;
            String operation = "{\"id\":" + id + "}";
            api("startJob", operation);
            await(() -> hasResult(id, 200), 20);
            api("stopJob", operation);
            assertEquals(0, jobs.loadById(id).getTriggerStatus());
            api("triggerJob", "{\"id\":" + id + ",\"executorParam\":\"fail\",\"addressList\":\"" + address + "\"}");
            await(() -> hasResult(id, 500), 10);
            List<XxlJobLog> records = logs.pageList(0, 100, 1, id, null, null, 0);
            assertTrue(records.stream().allMatch(log -> log.getTriggerCode() == 200));
            assertTrue(records.stream().anyMatch(log -> log.getHandleCode() == 200 && log.getHandleTime() != null));
            assertTrue(records.stream().anyMatch(log -> log.getHandleCode() == 500 && log.getHandleMsg().contains("预期失败")));
            api("removeJob", operation);
            assertNull(jobs.loadById(id));
            jobId = 0;
        } finally {
            if (jobId > 0) {
                XxlJobInfo job = jobs.loadById(jobId);
                if (job != null) { job.setTriggerStatus(0); jobs.update(job); }
                jobs.delete(jobId);
                logs.delete(jobId);
            }
            executor.destroy();
            groups.update(original);
        }
    }
}
