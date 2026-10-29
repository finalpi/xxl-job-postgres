package com.xxl.job.admin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xxl.job.admin.business.mapper.*;
import com.xxl.job.admin.business.model.*;
import com.xxl.job.admin.business.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.job.admin.business.scheduler.compat.*;
import com.xxl.job.core.constant.Const;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.openapi.executor.ExecutorBiz;
import com.xxl.job.core.openapi.executor.dto.*;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用独立 JVM 的真实 3.4.0 SDK 与当前执行器混合运行，验证协议双向兼容。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Legacy340EndToEndTest {
    @Value("${local.server.port}") int adminPort;
    @Resource XxlJobGroupMapper groups;
    @Resource XxlJobInfoMapper jobs;
    @Resource XxlJobLogMapper logs;
    @Resource XxlJobRegistryMapper registry;
    @Resource ExecutorClientFactory clients;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private JsonObject api(String method, String payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adminPort + "/api/" + method))
                .timeout(Duration.ofSeconds(5)).header(Const.XXL_JOB_APPNAME, "xxl-job-executor-sample")
                .header(Const.XXL_JOB_ACCESS_TOKEN, "default_token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(200, result.get("code").getAsInt(), result.toString());
        return result;
    }

    private void await(BooleanSupplier condition, int seconds, Path fixtureLog) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("旧执行器验证超时：" + (Files.exists(fixtureLog) ? Files.readString(fixtureLog) : "no fixture log"));
    }

    private List<XxlJobLog> records(int id) { return logs.pageList(0, 100, 1, id, null, null, 0); }

    private void stopFixture(Process process) throws Exception {
        if (process == null || !process.isAlive()) return;
        process.getOutputStream().close();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.4.0", "2.4.0"})
    void realLegacyExecutorRegistersRunsCronReadsLogsFailsKillsAndCoexistsWithCurrent(String sdkVersion, @TempDir Path temp) throws Exception {
        Path fixture = Path.of(System.getProperty("legacy.fixture.directory"));
        if ("2.4.0".equals(sdkVersion)) fixture = fixture.resolveSibling("legacy24-executor");
        assertTrue(Files.exists(fixture.resolve("target/legacy-classpath.txt")), "请用 -pl xxl-job-admin -am 构建测试");
        String classpath = fixture.resolve("target/classes") + File.pathSeparator
                + Files.readString(fixture.resolve("target/legacy-classpath.txt")).trim();
        assertTrue(classpath.contains("xxl-job-core-" + sdkVersion + ".jar"));
        assertFalse(classpath.contains("xxl-job-core-3.5"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        int oldPort = freePort();
        String oldAddress = "http://127.0.0.1:" + oldPort + "/";
        Path fixtureLog = temp.resolve("legacy-process.log");
        Process old = null;
        XxlJobExecutor current = new XxlJobExecutor();
        boolean currentStarted = false;
        XxlJobGroup original = groups.load(1);
        int jobId = 0;
        try {
            old = new ProcessBuilder(java, "-cp", classpath, "io.github.finalpi.compat.Legacy340ExecutorMain",
                    "http://127.0.0.1:" + adminPort, "xxl-job-executor-sample", "default_token",
                    String.valueOf(oldPort), temp.resolve("old-logs").toString(), sdkVersion)
                    .redirectErrorStream(true).redirectOutput(fixtureLog.toFile()).start();
            await(() -> "V3_4_0".equals(registry.findExecutorProtocol("xxl-job-executor-sample", oldAddress, oldAddress)), 20, fixtureLog);
            assertTrue(Files.readString(fixtureLog).contains("SDK=" + sdkVersion));

            int newPort = freePort();
            String newAddress = "http://127.0.0.1:" + newPort + "/";
            current.setAdminAddresses("http://127.0.0.1:" + adminPort);
            current.setAppname("xxl-job-executor-sample");
            current.setAccessToken("default_token");
            current.setIp("127.0.0.1");
            current.setPort(newPort);
            current.setLogPath(temp.resolve("current-logs").toString());
            current.registryJobHandler("legacyProbe", new IJobHandler() {
                @Override public void execute() { XxlJobHelper.handleSuccess("CURRENT_MIXED_SUCCESS"); }
            });
            current.start();
            currentStarted = true;
            await(() -> "CURRENT".equals(registry.findExecutorProtocol("xxl-job-executor-sample", newAddress, newAddress)), 15, fixtureLog);

            XxlJobGroup manual = groups.load(1);
            manual.setAddressType(1);
            manual.setAddressList(oldAddress);
            groups.update(manual);
            await(() -> oldAddress.equals(XxlJobAdminBootstrap.getInstance().getJobRegistryHelper()
                    .loadByAppName("xxl-job-executor-sample").getAddressList()), 40, fixtureLog);
            assertInstanceOf(LegacyExecutorAdapter.class, clients.get(oldAddress, manual));
            assertFalse(clients.get(newAddress, manual) instanceof LegacyExecutorAdapter);

            jobId = api("addJob", """
                    {"jobGroup":1,"name":"Legacy 3.4.0 E2E","author":"test","scheduleType":"CRON",
                    "scheduleConf":"0/1 * * * * ?","misfireStrategy":"DO_NOTHING",
                    "executorRouteStrategy":"FIRST","executorHandler":"legacyProbe",
                    "executorBlockStrategy":"SERIAL_EXECUTION","glueType":"BEAN"}
                    """).get("data").getAsInt();
            int id = jobId;
            String operation = "{\"id\":" + id + "}";
            api("startJob", operation);
            await(() -> records(id).stream().anyMatch(l -> l.getHandleCode() == 200 && "LEGACY_340_SUCCESS".equals(l.getHandleMsg())), 20, fixtureLog);
            api("stopJob", operation);
            assertEquals(0, jobs.loadById(id).getTriggerStatus());
            // 上游时间轮预读 5 秒，停止后先等已入轮调度耗尽，再验证手工执行和停机。
            Thread.sleep(6000);

            XxlJobLog success = records(id).stream().filter(l -> l.getHandleCode() == 200).findFirst().orElseThrow();
            ExecutorBiz legacyClient = clients.get(oldAddress, manual);
            assertTrue(legacyClient.beat().isSuccess());
            var log = legacyClient.log(new LogRequest(success.getId(), success.getTriggerTime().getTime(), 1));
            assertTrue(log.isSuccess());
            assertTrue(log.getData().getLogContent().contains("LEGACY_340_PROBE"), log.getData().getLogContent());

            api("triggerJob", "{\"id\":" + id + ",\"executorParam\":\"fail\",\"addressList\":\"" + oldAddress + "\"}");
            await(() -> records(id).stream().anyMatch(l -> l.getHandleCode() == 500 && l.getHandleMsg().contains("LEGACY_340_EXPECTED_FAILURE")), 15, fixtureLog);
            api("triggerJob", "{\"id\":" + id + ",\"addressList\":\"" + newAddress + "\"}");
            await(() -> records(id).stream().anyMatch(l -> l.getHandleCode() == 200 && "CURRENT_MIXED_SUCCESS".equals(l.getHandleMsg())), 15, fixtureLog);

            api("triggerJob", "{\"id\":" + id + ",\"executorParam\":\"sleep\",\"addressList\":\"" + oldAddress + "\"}");
            await(() -> !legacyClient.idleBeat(new IdleBeatRequest(id)).isSuccess(), 10, fixtureLog);
            await(() -> records(id).stream().anyMatch(l -> "sleep".equals(l.getExecutorParam()) && l.getTriggerCode() == 200), 10, fixtureLog);
            long sleepLog = records(id).stream().filter(l -> "sleep".equals(l.getExecutorParam())).findFirst().orElseThrow().getId();
            assertTrue(legacyClient.kill(new KillRequest(id)).isSuccess());
            await(() -> logs.load(sleepLog).getHandleCode() == 500, 15, fixtureLog);

            stopFixture(old);
            assertEquals(0, old.exitValue(), Files.readString(fixtureLog));
            await(() -> registry.findExecutorProtocol("xxl-job-executor-sample", oldAddress, oldAddress) == null, 10, fixtureLog);
            assertEquals("CURRENT", registry.findExecutorProtocol("xxl-job-executor-sample", newAddress, newAddress));
            api("removeJob", operation);
            assertNull(jobs.loadById(id));
            jobId = 0;
        } finally {
            if (jobId > 0) {
                XxlJobInfo job = jobs.loadById(jobId);
                if (job != null) { job.setTriggerStatus(0); jobs.update(job); }
            }
            stopFixture(old);
            if (currentStarted) current.destroy();
            if (jobId > 0) { jobs.delete(jobId); logs.delete(jobId); }
            groups.update(original);
        }
    }
}
