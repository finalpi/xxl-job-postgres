package com.xxl.job.admin.business.scheduler.thread;

import com.xxl.job.admin.business.model.XxlJobLogReport;
import com.xxl.job.admin.business.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.tool.concurrent.CyclicThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * job log report helper
 *
 * @author xuxueli 2019-11-22
 */
public class JobLogReportHelper {
    private static final Logger logger = LoggerFactory.getLogger(JobLogReportHelper.class);

    private CyclicThread logReportThread;
    private AtomicLong lastCleanLogTime;

    /**
     * start
     */
    public void start(){

        /**
         * last clean log time  （ Thread-safe concurrent reading and writing ）
          */
        lastCleanLogTime = new AtomicLong(0);

        // log report thread
        logReportThread = new CyclicThread("JobLogReportHelper#logReportThread", true, new Runnable() {
            @Override
            public void run() {

                // 每小时执行一次分区维护；失败不推进时间，下轮重试。
                if (System.currentTimeMillis() - lastCleanLogTime.get() >= 60 * 60 * 1000L) {
                    try {
                        int result = XxlJobAdminBootstrap.getInstance().getLogPartitionManager()
                                .maintain(XxlJobAdminBootstrap.getInstance().getLogretentiondays());
                        if (result >= 0) {
                            lastCleanLogTime.set(System.currentTimeMillis());
                        }
                    } catch (Exception e) {
                        logger.warn("日志分区维护失败，将在下一轮重试", e);
                    }
                }

                // 1、log-report refresh: refresh log report in 3 days
                for (int i = 0; i < 3; i++) {

                    // today
                    Calendar itemDay = Calendar.getInstance();
                    itemDay.add(Calendar.DAY_OF_MONTH, -i);
                    itemDay.set(Calendar.HOUR_OF_DAY, 0);
                    itemDay.set(Calendar.MINUTE, 0);
                    itemDay.set(Calendar.SECOND, 0);
                    itemDay.set(Calendar.MILLISECOND, 0);

                    Date todayFrom = itemDay.getTime();

                    itemDay.set(Calendar.HOUR_OF_DAY, 23);
                    itemDay.set(Calendar.MINUTE, 59);
                    itemDay.set(Calendar.SECOND, 59);
                    itemDay.set(Calendar.MILLISECOND, 999);

                    Date todayTo = itemDay.getTime();

                    // refresh log-report every minute
                    XxlJobLogReport xxlJobLogReport = new XxlJobLogReport();
                    xxlJobLogReport.setTriggerDay(todayFrom);
                    xxlJobLogReport.setRunningCount(0);
                    xxlJobLogReport.setSucCount(0);
                    xxlJobLogReport.setFailCount(0);
                    xxlJobLogReport.setUpdateTime(new Date());

                    // fill count-data
                    Map<String, Object> triggerCountMap = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().findLogReport(todayFrom, todayTo);
                    if (triggerCountMap!=null && !triggerCountMap.isEmpty()) {
                        int triggerDayCount = triggerCountMap.containsKey("triggerDayCount")?Integer.parseInt(String.valueOf(triggerCountMap.get("triggerDayCount"))):0;
                        int triggerDayCountRunning = triggerCountMap.containsKey("triggerDayCountRunning")?Integer.parseInt(String.valueOf(triggerCountMap.get("triggerDayCountRunning"))):0;
                        int triggerDayCountSuc = triggerCountMap.containsKey("triggerDayCountSuc")?Integer.parseInt(String.valueOf(triggerCountMap.get("triggerDayCountSuc"))):0;
                        int triggerDayCountFail = triggerDayCount - triggerDayCountRunning - triggerDayCountSuc;

                        xxlJobLogReport.setRunningCount(triggerDayCountRunning);
                        xxlJobLogReport.setSucCount(triggerDayCountSuc);
                        xxlJobLogReport.setFailCount(triggerDayCountFail);
                    }

                    // do refresh:
                    XxlJobAdminBootstrap.getInstance().getXxlJobLogReportMapper().saveOrUpdate(xxlJobLogReport);      // 0-fail; 1-save suc; 2-update suc;
                        /*if (ret < 1) {
                            XxlJobAdminBootstrap.getInstance().getXxlJobLogReportMapper().save(xxlJobLogReport);
                        }*/
                }


            }
        }, 60 * 1000L, true);
        logReportThread.start();

    }

    /**
     * stop
     */
    public void stop(){
        logReportThread.stop();
    }

}
