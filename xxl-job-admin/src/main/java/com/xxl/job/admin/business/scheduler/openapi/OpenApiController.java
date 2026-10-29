package com.xxl.job.admin.business.scheduler.openapi;

import com.xxl.job.admin.business.model.XxlJobGroup;
import com.xxl.job.admin.business.scheduler.compat.*;
import com.xxl.job.core.openapi.admin.dto.CallbackData;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xxl.job.admin.business.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.job.core.constant.Const;
import com.xxl.job.core.openapi.admin.AdminBiz;
import com.xxl.job.core.openapi.admin.AdminJobBiz;
import com.xxl.job.core.openapi.admin.dto.CallbackRequest;
import com.xxl.job.core.openapi.admin.dto.JobAddRequest;
import com.xxl.job.core.openapi.admin.dto.JobOperateRequest;
import com.xxl.job.core.openapi.admin.dto.JobTriggerRequest;
import com.xxl.job.core.openapi.admin.dto.JobUpdateRequest;
import com.xxl.job.core.openapi.admin.dto.RegistryRequest;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.json.GsonTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * Created by xuxueli on 17/5/10.
 */
@Controller
public class OpenApiController {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiController.class);

    @Resource
    private LegacyExecutorSupport legacySupport;

    @Resource
    private AdminBiz adminBiz;

    @Resource
    private AdminJobBiz adminJobBiz;

    /**
     * api
     */
    @RequestMapping("/api/{uri}")
    @ResponseBody
    @XxlSso(login = false)
    public Object api(HttpServletRequest request,
                      @PathVariable("uri") String uri,
                      @RequestHeader(value = Const.XXL_JOB_ACCESS_TOKEN, required = false) String accessToken,
                      @RequestHeader(value = Const.XXL_JOB_APPNAME, required = false) String appname,
                      @RequestBody(required = false) String requestBody) {

        // valid
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return Response.ofFail("invalid request, HttpMethod not support.");
        }
        if (StringTool.isBlank(uri)) {
            return Response.ofFail("invalid request, uri-mapping empty.");
        }
        if (StringTool.isBlank(requestBody)) {
            return Response.ofFail("invalid request, requestBody empty.");
        }

        // 只有完全缺少应用名请求头才进入旧协议；空或错误的请求头不能绕过新版鉴权。
        boolean legacy = appname == null;
        if (legacy) {
            if (!legacySupport.isEnabled() || !("registry".equals(uri) || "registryRemove".equals(uri) || "callback".equals(uri))) {
                return Response.ofFail("legacy executor API is disabled or unsupported.");
            }
        } else {
            if (StringTool.isBlank(accessToken) || StringTool.isBlank(appname)) {
                return Response.ofFail("invalid request, accessToken or appname is empty.");
            }
            XxlJobGroup group = XxlJobAdminBootstrap.getInstance().getJobRegistryHelper().loadByAppName(appname);
            if (group == null || !accessToken.equals(group.getAccessToken())) {
                return Response.ofFail("invalid request, accessToken or appname invalid.");
            }
        }

        // dispatch request
        try {
            switch (uri) {
                case "callback": {
                    List<CallbackData> callbacks;
                    if (legacy) {
                        List<LegacyCallbackData> oldCallbacks = GsonTool.fromJsonList(requestBody, LegacyCallbackData.class);
                        callbacks = oldCallbacks == null ? null : oldCallbacks.stream()
                                .map(item -> item == null ? null : item.toCurrent()).toList();
                    } else {
                        CallbackRequest callbackRequest = GsonTool.fromJson(requestBody, CallbackRequest.class);
                        callbacks = callbackRequest == null ? null : callbackRequest.getCallbackList();
                    }
                    if (!legacySupport.authorizeCallbacks(accessToken, appname, callbacks)) {
                        logger.warn("拒绝执行器回调，appname={}，legacy={}", appname, legacy);
                        return Response.ofFail("invalid callback identity or payload.");
                    }
                    return adminBiz.callback(new CallbackRequest(callbacks));
                }
                case "registry":
                case "registryRemove": {
                    RegistryRequest registryRequest = GsonTool.fromJson(requestBody, RegistryRequest.class);
                    if (legacy) {
                        if (!legacySupport.authorizeRegistry(accessToken, registryRequest)) {
                            return Response.ofFail("invalid legacy executor identity.");
                        }
                    } else if (registryRequest == null || !appname.equals(registryRequest.getRegistryKey())
                            || !"EXECUTOR".equals(registryRequest.getRegistryGroup())) {
                        return Response.ofFail("registry identity does not match appname.");
                    }
                    if ("registryRemove".equals(uri)) return adminBiz.registryRemove(registryRequest);
                    return XxlJobAdminBootstrap.getInstance().getJobRegistryHelper().registry(registryRequest,
                            legacy ? ExecutorProtocol.V3_4_0 : ExecutorProtocol.CURRENT);
                }
                case "addJob": {
                    JobAddRequest jobParam = GsonTool.fromJson(requestBody, JobAddRequest.class);
                    return adminJobBiz.addJob(jobParam);
                }
                case "updateJob": {
                    JobUpdateRequest jobParam = GsonTool.fromJson(requestBody, JobUpdateRequest.class);
                    return adminJobBiz.updateJob(jobParam);
                }
                case "removeJob": {
                    JobOperateRequest jobParam = GsonTool.fromJson(requestBody, JobOperateRequest.class);
                    return adminJobBiz.removeJob(jobParam);
                }
                case "startJob": {
                    JobOperateRequest jobParam = GsonTool.fromJson(requestBody, JobOperateRequest.class);
                    return adminJobBiz.startJob(jobParam);
                }
                case "stopJob": {
                    JobOperateRequest jobParam = GsonTool.fromJson(requestBody, JobOperateRequest.class);
                    return adminJobBiz.stopJob(jobParam);
                }
                case "triggerJob": {
                    JobTriggerRequest jobParam = GsonTool.fromJson(requestBody, JobTriggerRequest.class);
                    return adminJobBiz.triggerJob(jobParam);
                }
                default:
                    return Response.ofFail("invalid request, uri-mapping("+ uri +") not found.");
            }
        } catch (Exception e) {
            logger.warn("执行器接口请求处理失败，uri={}，appname={}，legacy={}", uri, appname, legacy, e);
            return Response.ofFail("openapi invoke error: " + e.getMessage());
        }

    }

}
