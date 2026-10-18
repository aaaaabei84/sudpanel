package com.sudpanel.config;

import org.springframework.boot.context.properties.ConfigurationProperties; // 配置绑定注解：把 sudpanel.* 前缀的配置项绑定到本 record

/**
 * 业务配置。绑定 application.properties 中 sudpanel.* 前缀。
 * 构造器绑定：属性缺失时用默认值兜底。
 */
@ConfigurationProperties(prefix = "sudpanel")
public record AppProperties(
        String adbPath,                // adb 可执行文件的绝对路径
        long waitMs,                   // 通用等待时长（毫秒）：动作后的稳定等待/轮询间隔
        String dbPath,                 // SQLite 数据库文件路径（默认 ~/.sudpanel-open/data/sudpanel.db）
        String resourceIdFile,         // 控件 resource-id 配置文件的路径
        int perfLoopCount,             // 性能测试：总循环轮数
        int perfSampleSeconds,         // 性能测试：每轮采样时长（秒）
        int perfListSampleSeconds,     // 性能测试：列表场景的采样时长（秒）
        long perfSampleIntervalMs,     // 性能测试：两次采样之间的间隔（毫秒）
        long perfEarlyTapDelayMs,      // 性能测试：提前点击的延迟（毫秒）
        long perfTapSettleMs,          // 性能测试：点击后的稳定等待时长（毫秒）
        long perfPlayingWaitMs,        // 性能测试：对局中的等待时长（毫秒）
        double perfCpuThreshold,       // 性能测试：CPU 占用率告警阈值（整机口径 %，v1.5 #21）
        double perfMemGrowthPercent,   // 性能测试：内存增长率告警阈值（百分比）
        double perfMemGrowthAbsMb,     // 性能测试：内存绝对增量告警阈值（MB）——与百分比构成双判据
        int perfWarmupLoops,           // 性能测试：预热轮数（正式采样前先跑几轮热机）
        int perfMaxConsecutiveInvalid, // 性能测试：连续无效采样的最大容忍次数
        String notifyWebhook,          // 中断推送 webhook URL（飞书/自定义）；空 = 不推送
        int logcatTailLines,           // 上报查询：每次轮询最多取回 logcat 尾部多少行（#60）
        double perfAttrSharePercent,   // 归因：某一分项占总增量的比例超过它就点名（#80）
        double perfAttrMinR2,          // 归因：线性拟合度门槛，低于它说明趋势不可信（#83）
        double perfReclaimableCacheMb  // 可回收缓存量阈值（MB）：回收前/回收后基线之差（#84）
) {
    public AppProperties {
        if (adbPath == null || adbPath.isBlank()) {
            adbPath = "disabled";
        }
        if (waitMs <= 0) {
            waitMs = 2000L;
        }
        if (dbPath == null || dbPath.isBlank()) {
            dbPath = System.getProperty("user.home") + "/.sudpanel-open/data/sudpanel.db";
        }
        if (resourceIdFile == null || resourceIdFile.isBlank()) {
            resourceIdFile = System.getProperty("user.home") + "/.sudpanel-open/config/resource-ids.properties";
        }
        if (perfLoopCount <= 0) {
            perfLoopCount = 50;
        }
        if (perfSampleSeconds <= 0) {
            perfSampleSeconds = 5;
        }
        if (perfListSampleSeconds <= 0) {
            perfListSampleSeconds = 2;
        }
        if (perfSampleIntervalMs <= 0) {
            perfSampleIntervalMs = 500L;
        }
        if (perfEarlyTapDelayMs <= 0) {
            perfEarlyTapDelayMs = 700L;
        }
        if (perfTapSettleMs <= 0) {
            perfTapSettleMs = 400L;
        }
        if (perfPlayingWaitMs <= 0) {
            perfPlayingWaitMs = 2500L;
        }
        if (perfCpuThreshold <= 0) {
            perfCpuThreshold = 15.0;
        }
        if (perfMemGrowthPercent <= 0) {
            perfMemGrowthPercent = 20.0;
        }
        if (perfMemGrowthAbsMb <= 0) {
            perfMemGrowthAbsMb = 50.0;
        }
        if (perfWarmupLoops < 0) {
            perfWarmupLoops = 5;
        }
        if (perfMaxConsecutiveInvalid <= 0) {
            perfMaxConsecutiveInvalid = 3;
        }
        if (logcatTailLines <= 0) {
            logcatTailLines = 2000;
        }
        if (perfAttrSharePercent <= 0) {
            perfAttrSharePercent = 60.0;
        }
        if (perfAttrMinR2 <= 0) {
            perfAttrMinR2 = 0.5;
        }
        if (perfReclaimableCacheMb <= 0) {
            perfReclaimableCacheMb = 30.0;
        }
    }
}
