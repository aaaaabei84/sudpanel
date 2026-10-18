package com.sudpanel;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.sudpanel.config.AppProperties;

/**
 * SudPanel 后端入口（Spring Boot 启动类）。
 *
 * <p>开发时可直接运行 main 或 mvn spring-boot:run；JAR 通过
 * {@link com.sudpanel.launcher.Launcher} 调用同一入口。
 * 展示版默认配置使用独立本地数据目录，不复制运行库、业务配置或设备工具。
 *
 * <p>本类除 main 外还声明了三个 Bean：
 * <ul>
 *   <li>{@link #dataSource}：SQLite 数据源（驱动 org.sqlite.JDBC，jdbc:sqlite: 协议）；</li>
 *   <li>{@link #jdbcTemplate}：Spring JDBC 模板（所有 Repository 用它执行 SQL）；</li>
 *   <li>{@link #openBrowserOnStart}：兼容原生壳的可选浏览器钩子，默认不触发。</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SudPanelApplication {

    public static void main(String[] args) {
        // 启动 Spring Boot 应用（自动扫描 com.sudpanel 包下的组件）
        SpringApplication.run(SudPanelApplication.class, args);
    }

    /**
     * 创建 SQLite 数据源。
     *
     * <p>数据库路径来自配置 {@code sudpanel.db-path}；展示版默认配置位于独立本地数据目录。
     * 首次连接前创建父目录，避免 SQLite 因目录缺失而建库失败。
     *
     * @param props 配置绑定对象（sudpanel.* 前缀）
     * @return 指向 SQLite 文件的 DataSource
     */
    @Bean(destroyMethod = "close")
    DataSource dataSource(AppProperties props) throws Exception {
        // 把配置的路径转绝对路径（相对路径以启动时工作目录为基准，这里统一规范化）
        Path db = Path.of(props.dbPath()).toAbsolutePath();
        if (db.getParent() != null) {
            // SQLite 驱动不会自动建目录，父目录不存在必须先建
            Files.createDirectories(db.getParent());
        }
        return com.sudpanel.backup.OfflineRestore.open(db);
    }

    /**
     * 创建 JdbcTemplate（Spring 封装的 JDBC 工具，自动管理连接/结果集映射）。
     *
     * <p>各 Repository（TestPointRepository / PerfRepository 等）通过构造注入使用它，
     * 统一走这一个数据源。
     */
    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 兼容原生壳的可选浏览器钩子，展示版 JAR 默认不触发。
     *
     * <p>ApplicationRunner 在服务就绪后执行；仅显式设置系统属性
     * {@code sudpanel.packaged=true} 时打开浏览器。打开失败不会阻塞启动。
     */
    @Bean
    ApplicationRunner openBrowserOnStart(org.springframework.core.env.Environment environment) {
        return args -> {
            if ("true".equals(System.getProperty("sudpanel.packaged"))) {
                String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "18890"));
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI.create("http://localhost:" + port));
                    }
                } catch (IOException ignored) {
                    // 打不开浏览器不阻塞启动
                }
            }
        };
    }
}
