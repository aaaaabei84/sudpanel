package com.sudpanel.locator;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 从 uiautomator dump 的 XML 中按 resource-id 定位控件中心坐标。
 * 用 DOM 解析而非字符串截取，保证属性顺序变化时依然稳定。
 */
// ===== 设计要点 =====
// 1. 输入：AdbClient.uiDump() 拿到的屏幕控件树 XML（uiautomator dump 生成的 <hierarchy> 文档）；
// 2. 控件定位依据：resource-id 属性（布局里 android:id 去掉包名前缀后的短 id，如 app_common_self_in）；
// 3. 每个控件节点带 bounds 属性，形如 [x1,y1][x2,y2]（控件矩形左上/右下角坐标），
//    本类算出的中心点坐标直接交给 AdbClient.tap() 做真实点击，形成"看到控件→算出坐标→点下去"的闭环；
// 4. DOM 解析对属性顺序不敏感，比正则/字符串截取更健壮。
@Component
public class UiLocator {

    // 预编译正则：解析控件 bounds 属性 "[x1,y1][x2,y2]" 并捕获四个坐标数字。
    // 例："[x1,y1][x2,y2]"（真实 bounds 形如 [左,上][右,下]，四个整数）→ group(1..4)。
    // 编译一次反复使用，避免每次解析都重复编译正则的开销。
    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    /**
     * 按 resource-id 找全部节点，返回文本+中心坐标。用于列表扫描（游戏名列表）。
     */
    // @param xml         屏幕控件树 XML 原文（来自 AdbClient.uiDump()）
    // @param resourceId  目标控件 id（如游戏名列表项的 resource-id）
    // @return 所有匹配控件的 TextNode 列表（text + 中心坐标）；无匹配/解析失败时为空列表
    public List<TextNode> findByResourceId(String xml, String resourceId) {
        List<TextNode> out = new ArrayList<>();
        // 入参为空或空白：直接返回空列表，避免无意义的解析开销
        if (xml == null || xml.isBlank() || resourceId == null || resourceId.isBlank()) {
            return out;
        }
        try {
            // DOM 解析：把 XML 字符串解析成内存中的文档树；
            // StringReader 把字符串包装成字符流，InputSource 供解析器读取；setNamespaceAware(false) 忽略命名空间
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            // uiautomator 的每个控件对应一个 <node> 标签，取全部 node 逐一检查
            NodeList nodes = doc.getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                // 只看 resource-id 属性与目标完全一致的节点
                if (resourceId.equals(e.getAttribute("resource-id"))) {
                    // 解析 bounds 属性，取出控件矩形四角坐标
                    Matcher m = BOUNDS.matcher(e.getAttribute("bounds"));
                    if (m.find()) {
                        // 左上角坐标
                        int x1 = Integer.parseInt(m.group(1));
                        int y1 = Integer.parseInt(m.group(2));
                        // 右下角坐标
                        int x2 = Integer.parseInt(m.group(3));
                        int y2 = Integer.parseInt(m.group(4));
                        // 中心点 = 两组对应坐标取平均（整数除法向下取整，亚像素误差可忽略）
                        out.add(new TextNode(e.getAttribute("text"), (x1 + x2) / 2, (y1 + y2) / 2));
                    }
                }
            }
        } catch (Exception e) {
            // 解析异常（XML 不完整/非法等）：返回已收集的部分结果，不向上抛异常，
            // 由调用方按"没找到控件"处理（如重试或跳过）
            return out;
        }
        return out;
    }

    /**
     * 按文本精确匹配定位控件中心坐标（游戏名点选用）。找不到返回 null。
     */
    // @param xml         屏幕控件树 XML 原文
    // @param resourceId  列表控件（如游戏名列表项）的 resource-id
    // @param text        目标文本（如某个游戏名），必须与控件 text 属性完全相等
    // @return 命中的控件中心坐标 [x, y]；无命中返回 null
    public int[] locateByText(String xml, String resourceId, String text) {
        // 先按 resource-id 取整组同 id 控件，再逐个比对文本（一次解析、多次比对）
        for (TextNode n : findByResourceId(xml, resourceId)) {
            // equals 精确匹配：防止"欢乐斗地主"误中"欢乐斗地主体验版"这类包含关系
            if (text.equals(n.text())) {
                return new int[]{n.x(), n.y()};
            }
        }
        // 列表里没有目标文本（可能还没加载出来或名字不同）：返回 null，由上层决定重试或跳过
        return null;
    }

    /** 列表条目：文本 + 中心坐标。 */
    // record 组件：text=控件文本（可能为空串），x/y=控件中心点坐标（整数）
    public record TextNode(String text, int x, int y) {
    }

    /**
     * @return [centerX, centerY]，找不到返回 null
     */
    // 定位单个控件的中心坐标（适用于页面上该 resource-id 唯一的场景，如某个按钮）。
    // @param xml         屏幕控件树 XML 原文
    // @param resourceId  目标控件的 resource-id
    public int[] locateCenter(String xml, String resourceId) {
        // 入参校验：为空直接返回 null
        if (xml == null || xml.isBlank() || resourceId == null || resourceId.isBlank()) {
            return null;
        }
        try {
            // 与 findByResourceId 相同的 DOM 解析流程
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList nodes = doc.getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                // 命中目标 resource-id
                if (resourceId.equals(e.getAttribute("resource-id"))) {
                    Matcher m = BOUNDS.matcher(e.getAttribute("bounds"));
                    if (m.find()) {
                        // 取矩形左上/右下角坐标，算出中心点后立即返回（只处理第一个命中节点）
                        int x1 = Integer.parseInt(m.group(1));
                        int y1 = Integer.parseInt(m.group(2));
                        int x2 = Integer.parseInt(m.group(3));
                        int y2 = Integer.parseInt(m.group(4));
                        return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败与"找不到"语义一致，统一返回 null
            return null;
        }
        return null;
    }
}
