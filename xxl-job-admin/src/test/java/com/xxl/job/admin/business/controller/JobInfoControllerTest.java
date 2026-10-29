package com.xxl.job.admin.business.controller;

import com.xxl.sso.core.constant.Const;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;

public class JobInfoControllerTest extends AbstractSpringMvcTest {
  private static Logger logger = LoggerFactory.getLogger(JobInfoControllerTest.class);

  private Cookie cookie;

  @BeforeEach
  public void login() throws Exception {
    MvcResult ret = mockMvc.perform(
        post("/auth/doLogin")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("userName", "admin")
            .param("password", "123456")
    ).andReturn();
    assertTrue(ret.getResponse().getContentAsString().contains("\"code\":200"));
    cookie = ret.getResponse().getCookie("xxl_job_login_token");
    assertNotNull(cookie);
  }

  @Test
  public void testAdd() throws Exception {
    MultiValueMap<String, String> parameters = new LinkedMultiValueMap<String, String>();
    parameters.add("jobGroup", "1");
    parameters.add("triggerStatus", "-1");
    parameters.add("name", "");
    parameters.add("executorHandler", "");
    parameters.add("author", "");

    MvcResult ret = mockMvc.perform(
        post("/jobinfo/pageList")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            //.content(paramsJson)
            .params(parameters)
            .cookie(cookie)
    ).andExpect(status().isOk()).andReturn();

    assertTrue(ret.getResponse().getContentAsString().contains("\"code\":200"));
    assertTrue(ret.getResponse().getContentAsString().contains("demoJobHandler"));
  }

}
