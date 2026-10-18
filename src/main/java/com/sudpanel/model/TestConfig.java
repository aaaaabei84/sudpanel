package com.sudpanel.model;

import java.util.List;

/**
 * 一套测试点配置：用户在页面上勾选的一组测试点 + 顺序，保存后下次一键加载。
 *
 * <p>对应前端「配置」栏：保存为新配置 / 覆盖选中 / 下拉加载。持久化在 test_config 表。
 *
 * @param id        配置主键（数据库自增）
 * @param direction 属于哪个方向（APPTOMG / MGTOAPP），两个方向的配置互不干扰
 * @param name      配置名（用户起的名字，如「完整回归」「冒烟」）
 * @param pointIds  勾选的测试点 id 列表，顺序即执行顺序
 * @param createdAt 创建时间（ISO 字符串）
 */
public record TestConfig(
        Long id,
        Direction direction,
        String name,
        List<Long> pointIds,
        String createdAt
) {
}
