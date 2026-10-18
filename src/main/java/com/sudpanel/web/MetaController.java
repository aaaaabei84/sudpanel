package com.sudpanel.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台元信息接口：把「平台名 + 版本号」暴露给前端（首页/侧边栏展示用）。
 *
 * <p>【为什么单独一个接口，而不是前端写死版本号】
 * 版本号的事实来源是 pom.xml 的 {@code <version>}。若前端再写一份（如 package.json 或硬编码字符串），
 * 两处必然随迭代分叉——改了后端忘了改前端，界面显示的版本就是假的。
 * 这里用 Spring Boot 资源过滤（{@code @project.version@} 占位符）在构建期把 pom 版本注入
 * application.properties，运行时通过本接口读取，保证「显示的版本 = 实际构建的版本」。
 *
 * <p>【注意】{@code @project.version@} 仅在 Maven 构建产物（target/classes）中会被替换。
 * 若绕过 Maven 直接以源码目录启动，占位符不会被解析，此时返回默认值 "dev"。
 */
@RestController
@RequestMapping("/api")
public class MetaController {

    /** 平台名（配置文件 sudpanel.name，缺省 SudPanel）。 */
    private final String name;
    /** 版本号（配置文件 sudpanel.version，构建期由 pom.xml 注入；未注入时为 dev）。 */
    private final String version;

    /**
     * 构造器注入：@Value 从配置读取，冒号后是缺省值（配置缺失/占位符未替换时兜底）。
     * @param name    平台名
     * @param version 版本号
     */
    public MetaController(@Value("${sudpanel.name:SudPanel}") String name,
                          @Value("${sudpanel.version:dev}") String version) {
        this.name = name;
        this.version = version;
    }

    /**
     * 查询平台元信息（前端启动时拉一次，用于页面显示版本号）。
     * @return {@code {name, version}}；用 LinkedHashMap 保证字段顺序稳定，便于人工查看
     */
    @GetMapping("/meta")
    public Map<String, String> meta() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("version", version);
        return meta;
    }
}
