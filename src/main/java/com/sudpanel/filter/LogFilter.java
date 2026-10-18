package com.sudpanel.filter;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** logcat 文本按关键字过滤，返回所有命中行（不做上下文）。 */
// 用途：上报日志命中检测——测试开始前清空 logcat，触发动作后 dump 日志，
// 再按测试点配置的 keyword 过滤出命中行，作为"该状态确实上报了"的证据。
// 设计说明：
// 1. 只做"行包含关键字"的子串匹配，不做时间窗/上下文过滤（调用方已保证日志是本次测试期间产生的）；
// 2. 无状态纯函数工具，注册为 Spring 单例组件，供各 Service 注入使用。
@Component
public class LogFilter {

    /**
     * 过滤出所有包含关键字的日志行。
     *
     * @param logcat  完整 logcat 文本（多行字符串，行与行之间以 \n 分隔）
     * @param keyword 匹配关键字（子串匹配，非正则表达式）
     * @return 命中的日志行列表；入参为空/空白时返回空列表
     */
    public List<String> filter(String logcat, String keyword) {
        // 结果集合：按行命中顺序收集
        List<String> hits = new ArrayList<>();
        // 入参校验：logcat 或 keyword 为空/空白时没有匹配依据，直接返回空列表
        if (logcat == null || keyword == null || keyword.isBlank()) {
            return hits;
        }
        // 按行切分后逐行检查：contains 是子串匹配（如 keyword="isIn=true" 能命中
        // 形如 "...onEvent isIn=true..." 的日志行），命中即把整行收集起来
        for (String line : logcat.split("\n")) {
            if (line.contains(keyword)) {
                hits.add(line);
            }
        }
        return hits;
    }
}
