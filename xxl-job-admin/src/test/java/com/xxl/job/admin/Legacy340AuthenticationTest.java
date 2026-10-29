package com.xxl.job.admin;

import com.google.gson.JsonParser;
import com.xxl.job.admin.business.mapper.*;
import com.xxl.job.admin.business.model.*;
import com.xxl.job.admin.business.scheduler.compat.LegacyExecutorSupport;
import com.xxl.job.core.constant.Const;
import com.xxl.job.core.openapi.admin.dto.CallbackData;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证旧协议不能绕过组令牌、越组回调或调用新增管理接口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Legacy340AuthenticationTest {
    @Value("${local.server.port}") int port;
    @Resource XxlJobGroupMapper groups;
    @Resource XxlJobLogMapper logs;
    @Resource XxlJobRegistryMapper registry;
    private final HttpClient http = HttpClient.newHttpClient();

    private int request(String uri, String body, String token, String appname) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/" + uri))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (token != null) builder.header(Const.XXL_JOB_ACCESS_TOKEN, token);
        if (appname != null) builder.header(Const.XXL_JOB_APPNAME, appname);
        var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsInt();
    }

    private XxlJobLog log(int group) {
        XxlJobLog log = new XxlJobLog();
        log.setJobId(987655);
        log.setJobGroup(group);
        log.setTriggerTime(new Date());
        log.setTriggerCode(200);
        logs.save(log);
        return log;
    }

    private String callback(long id) {
        return "[{\"logId\":" + id + ",\"logDateTim\":123,\"handleCode\":200,\"handleMsg\":\"test\"}]";
    }

    @Test
    void legacyRegistryRejectsUnknownIdentityWrongTokenAndManagementCalls() throws Exception {
        String known = "{\"registryGroup\":\"EXECUTOR\",\"registryKey\":\"xxl-job-executor-sample\",\"registryValue\":\"http://127.0.0.1:1/\"}";
        assertNotEquals(200, request("registry", known, null, null));
        assertNotEquals(200, request("registry", known, "wrong-token", null));
        assertNotEquals(200, request("registryRemove", known, "wrong-token", null));
        assertNotEquals(200, request("registry", known.replace("xxl-job-executor-sample", "unknown-group"), "default_token", null));
        assertNotEquals(200, request("registry", known, "default_token", ""));
        assertNotEquals(200, request("registry", known, "default_token", "xxl-job-executor-sample-ai"));
        for (String method : List.of("addJob", "updateJob", "removeJob", "startJob", "stopJob", "triggerJob")) {
            assertNotEquals(200, request(method, "{}", "default_token", null));
        }
        assertNull(registry.findExecutorProtocol("xxl-job-executor-sample", "http://127.0.0.1:1/", "http://127.0.0.1:1/"));
    }

    @Test
    void callbacksAuthenticateWholeBatchBeforeEnqueueAndRespectGroupScope() throws Exception {
        XxlJobGroup original = groups.load(2);
        XxlJobLog first = log(1);
        XxlJobLog second = log(2);
        try {
            XxlJobGroup changed = groups.load(2);
            changed.setAccessToken("other-group-token");
            groups.update(changed);
            assertNotEquals(200, request("callback", callback(first.getId()), null, null));
            assertNotEquals(200, request("callback", callback(first.getId()), "wrong-token", null));
            assertNotEquals(200, request("callback", callback(second.getId()), "default_token", null));
            String mixed = callback(first.getId()).replace("]", "," + callback(second.getId()).substring(1));
            assertNotEquals(200, request("callback", mixed, "default_token", null));
            assertEquals(0, logs.load(first.getId()).getHandleCode());
            assertEquals(0, logs.load(second.getId()).getHandleCode());
            String currentCrossGroup = "{\"callbackList\":[{\"logId\":" + second.getId() + ",\"handleCode\":200}]}";
            assertNotEquals(200, request("callback", currentCrossGroup, "default_token", "xxl-job-executor-sample"));
            for (String invalid : List.of("[]", "[null]", "{}", "not-json", "[{\"logId\":1}]")) {
                assertNotEquals(200, request("callback", invalid, "default_token", null));
            }
            assertEquals(200, request("callback", callback(first.getId()), "default_token", null));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && logs.load(first.getId()).getHandleCode() != 200) Thread.sleep(50);
            assertEquals(200, logs.load(first.getId()).getHandleCode());
            assertEquals(200, request("callback", callback(Long.MAX_VALUE), "default_token", null));
        } finally {
            groups.update(original);
            logs.delete(987655);
        }
    }

    @Test
    void globalLegacyTokenOnlyAppliesToGroupsWithoutTheirOwnToken() {
        XxlJobGroup original = groups.load(2);
        XxlJobLog log = log(2);
        try {
            LegacyExecutorSupport support = new LegacyExecutorSupport(groups, logs, true, "old-global-token");
            var callbacks = List.of(new CallbackData(log.getId(), 0, 200, "test"));
            assertFalse(support.authorizeCallbacks("old-global-token", null, callbacks));
            XxlJobGroup blank = groups.load(2);
            blank.setAccessToken("");
            groups.update(blank);
            assertTrue(support.authorizeCallbacks("old-global-token", null, callbacks));
            assertFalse(support.authorizeCallbacks("default_token", null, callbacks));
            assertFalse(support.authorizeCallbacks("old-global-token", "xxl-job-executor-sample-ai", callbacks));
        } finally {
            groups.update(original);
            logs.delete(987655);
        }
    }

    @Test
    void largeLegacyBatchIsAuthorizedInBoundedQueries() {
        var ids = new ArrayList<Long>();
        try {
            for (int i = 0; i < 501; i++) ids.add(log(1).getId());
            var callbacks = ids.stream().map(id -> new CallbackData(id, 0, 200, "test")).toList();
            LegacyExecutorSupport support = new LegacyExecutorSupport(groups, logs, true, "");
            assertTrue(support.authorizeCallbacks("default_token", null, callbacks));
            assertFalse(support.authorizeCallbacks("wrong-token", null, callbacks));
        } finally { logs.delete(987655); }
    }
}
