package com.xxl.job.admin.business.mapper;

import com.xxl.job.admin.business.model.XxlJobRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * Created by xuxueli on 16/9/30.
 */
@Mapper
public interface XxlJobRegistryMapper {

    public List<Integer> findDead(@Param("timeout") int timeout,
                                  @Param("nowTime") Date nowTime);

    public int removeDead(@Param("ids") List<Integer> ids);

    public List<XxlJobRegistry> findAll(@Param("timeout") int timeout,
                                        @Param("nowTime") Date nowTime);

    default int registrySaveOrUpdate(String registryGroup, String registryKey, String registryValue, Date updateTime) {
        return registrySaveOrUpdateWithProtocol(registryGroup, registryKey, registryValue, updateTime, "CURRENT");
    }

    /** 保存心跳及已鉴权请求所使用的协议。 */
    int registrySaveOrUpdateWithProtocol(@Param("registryGroup") String registryGroup,
                            @Param("registryKey") String registryKey,
                            @Param("registryValue") String registryValue,
                            @Param("updateTime") Date updateTime,
                            @Param("executorProtocol") String executorProtocol);

    /** 查询指定地址的协议，兼容手工地址末尾斜杠的差异。 */
    String findExecutorProtocol(@Param("appname") String appname,
                               @Param("address") String address,
                               @Param("alternativeAddress") String alternativeAddress);

    /*public int registryUpdate(@Param("registryGroup") String registryGroup,
                              @Param("registryKey") String registryKey,
                              @Param("registryValue") String registryValue,
                              @Param("updateTime") Date updateTime);

    public int registrySave(@Param("registryGroup") String registryGroup,
                            @Param("registryKey") String registryKey,
                            @Param("registryValue") String registryValue,
                            @Param("updateTime") Date updateTime);*/

    public int registryDelete(@Param("registryGroup") String registryGroup,
                          @Param("registryKey") String registryKey,
                          @Param("registryValue") String registryValue);

    public int removeByRegistryGroupAndKey(@Param("registryGroup") String registryGroup,
                                           @Param("registryKey") String registryKey);

}
