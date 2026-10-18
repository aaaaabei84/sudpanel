package com.sudpanel.locator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UiLocator 纯函数测试（2026-10-02 补）。
 * 覆盖：按 resource-id 定位、中心坐标计算、多命中顺序、locateByText 精确匹配、
 * locateCenter 取首个、以及入参为空 / XML 非法 / bounds 形态异常等边界。
 *
 * Test-only：所有节点、包名和坐标均为构造的测试夹具，不来自设备导出。
 * 通过 {@link #bounds} 集中拼接属性，保持用例可读。
 */
class UiLocatorTest {

    private final UiLocator locator = new UiLocator();

    // ---- 测试夹具 ----

    /** 拼一份最小可解析的 uiautomator dump XML。 */
    private static String xml(String body) {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
                + "<hierarchy rotation=\"0\">" + body + "</hierarchy>";
    }

    /** 由合成矩形边界拼接 bounds 属性串。 */
    private static String bounds(int x1, int y1, int x2, int y2) {
        return "[" + x1 + "," + y1 + "][" + x2 + "," + y2 + "]";
    }

    private static String node(String rid, String text, String bounds) {
        return "<node index=\"0\" text=\"" + text + "\" resource-id=\"" + rid
                + "\" class=\"android.widget.Button\" bounds=\"" + bounds + "\" />";
    }

    // ---- 用例 ----

    @Test
    @DisplayName("按 resource-id 命中并算出中心坐标")
    void findsCenterCoordinate() {
        String x = xml(node("com.x:id/game_name", "某游戏", bounds(0, 300, 720, 380)));
        List<UiLocator.TextNode> ns = locator.findByResourceId(x, "com.x:id/game_name");
        assertThat(ns).hasSize(1);
        assertThat(ns.get(0).text()).isEqualTo("某游戏");
        assertThat(ns.get(0).x()).isEqualTo(360); // (0+720)/2
        assertThat(ns.get(0).y()).isEqualTo(340); // (300+380)/2
    }

    @Test
    @DisplayName("同 id 多节点按文档顺序全部返回")
    void returnsAllMatchesInDocumentOrder() {
        String x = xml(node("com.x:id/item", "A", bounds(0, 0, 100, 100))
                + node("com.x:id/other", "X", bounds(0, 0, 10, 10))
                + node("com.x:id/item", "B", bounds(200, 200, 300, 300)));
        List<UiLocator.TextNode> ns = locator.findByResourceId(x, "com.x:id/item");
        assertThat(ns).extracting(UiLocator.TextNode::text).containsExactly("A", "B");
    }

    @Test
    @DisplayName("无匹配节点返回空列表")
    void noMatchReturnsEmpty() {
        String x = xml(node("com.x:id/a", "A", bounds(0, 0, 2, 2)));
        assertThat(locator.findByResourceId(x, "com.x:id/zzz")).isEmpty();
    }

    @Test
    @DisplayName("入参为 null / 空白 时返回空列表，不抛异常")
    void blankInputsReturnEmpty() {
        assertThat(locator.findByResourceId(null, "a")).isEmpty();
        assertThat(locator.findByResourceId("   ", "a")).isEmpty();
        assertThat(locator.findByResourceId(xml(""), null)).isEmpty();
        assertThat(locator.findByResourceId(xml(""), "   ")).isEmpty();
    }

    @Test
    @DisplayName("XML 非法时吞掉异常，返回空列表（由调用方按『没找到』处理）")
    void malformedXmlDoesNotThrow() {
        assertThat(locator.findByResourceId("<hierarchy><node", "a")).isEmpty();
        assertThat(locator.findByResourceId("not-xml-at-all", "a")).isEmpty();
    }

    @Test
    @DisplayName("locateByText：精确匹配命中；包含/前缀关系不命中")
    void locateByTextIsExactMatch() {
        String x = xml(node("com.x:id/item", "某游戏", bounds(0, 0, 10, 20))
                + node("com.x:id/item", "某游戏体验版", bounds(0, 100, 10, 120)));
        assertThat(locator.locateByText(x, "com.x:id/item", "某游戏")).containsExactly(5, 10);
        // 「某游戏」是「某游戏体验版」的前缀，但 equals 精确匹配 → 不命中
        assertThat(locator.locateByText(x, "com.x:id/item", "某游")).isNull();
        assertThat(locator.locateByText(x, "com.x:id/item", "不存在")).isNull();
    }

    @Test
    @DisplayName("locateCenter：返回第一个命中节点（单控件场景）")
    void locateCenterReturnsFirstMatch() {
        String x = xml(node("com.x:id/b", "B1", bounds(0, 0, 100, 100))
                + node("com.x:id/b", "B2", bounds(200, 200, 400, 400)));
        assertThat(locator.locateCenter(x, "com.x:id/b")).containsExactly(50, 50);
    }

    @Test
    @DisplayName("locateCenter：无命中 / 入参为空 时返回 null")
    void locateCenterReturnsNull() {
        assertThat(locator.locateCenter(xml(""), "a")).isNull();
        assertThat(locator.locateCenter(null, "a")).isNull();
        assertThat(locator.locateCenter("   ", "a")).isNull();
    }

    @Test
    @DisplayName("⚠️ bounds 中 ][ 之间有空格时不匹配（正则要求两对方括号紧邻）")
    void boundsWithSpaceIsNotMatched() {
        String spacedBounds = "[" + 0 + "," + 0 + "] [" + 2 + "," + 2 + "]";
        String x = xml(node("com.x:id/a", "A", spacedBounds));
        assertThat(locator.findByResourceId(x, "com.x:id/a")).isEmpty();
    }

    @Test
    @DisplayName("⚠️ 负坐标当前不匹配 —— 与「允许负坐标」的设计描述不符（已上报用户待决策）")
    void negativeBoundsCurrentlyNotMatched() {
        // 解析正则不含负号 → 负坐标节点被整条跳过
        String x = xml(node("com.x:id/a", "A", bounds(-100, -100, 100, 100)));
        assertThat(locator.findByResourceId(x, "com.x:id/a")).isEmpty();
    }
}
