package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 返回码相关的两段纯逻辑测试：从响应体抠业务返回码、断言命中判断（v1.10）。
 *
 * <p>这两段最容易出「看起来对、实际误判」的问题，所以覆盖边界而不是走一遍主流程：
 * <ul>
 *   <li>抠码：字段优先级、包在 data 里、数组形式、不是 JSON、值是字符串、深层递归上限；</li>
 *   <li>断言：期望留空即不校验、实际为 null 时的行为、多个期望值、中文逗号、
 *       以及 {@code "0"} 与 {@code 0} 这类字符串/数字混写的比较。</li>
 * </ul>
 */
class CodeDictionaryTest {

    // ---------- 抠业务返回码 ----------

    @Test
    @DisplayName("顶层 ret_code 直接取值")
    void extractsTopLevelRetCode() {
        CodeDictionary.Found f = CodeDictionary.extract("{\"ret_code\":0,\"data\":{}}");
        assertThat(f).isNotNull();
        assertThat(f.raw()).isEqualTo("0");
        assertThat(f.field()).isEqualTo("ret_code");
    }

    @Test
    @DisplayName("ret_code 优先于 code —— 两个都在时取 ret_code（业务码，不是 HTTP 语义的那个）")
    void retCodeWinsOverCode() {
        CodeDictionary.Found f = CodeDictionary.extract("{\"code\":200,\"ret_code\":100503}");
        assertThat(f.field()).isEqualTo("ret_code");
        assertThat(f.raw()).isEqualTo("100503");
    }

    @Test
    @DisplayName("业务码包在 data 里也能抠到")
    void extractsFromNestedContainer() {
        CodeDictionary.Found f = CodeDictionary.extract("{\"ok\":true,\"data\":{\"code\":101004}}");
        assertThat(f).isNotNull();
        assertThat(f.raw()).isEqualTo("101004");
        assertThat(f.field()).isEqualTo("code");
    }

    @Test
    @DisplayName("响应是数组时取第一个元素")
    void extractsFromArray() {
        CodeDictionary.Found f = CodeDictionary.extract("[{\"errcode\":-1}]");
        assertThat(f).isNotNull();
        assertThat(f.raw()).isEqualTo("-1");
    }

    @Test
    @DisplayName("值是字符串也能取（有些服务端把码写成 \"0\"）")
    void extractsStringValue() {
        CodeDictionary.Found f = CodeDictionary.extract("{\"retCode\":\"0\"}");
        assertThat(f).isNotNull();
        assertThat(f.raw()).isEqualTo("0");
    }

    @Test
    @DisplayName("不是 JSON（HTML 错误页/纯文本）→ 返回 null，不瞎抓")
    void nonJsonReturnsNull() {
        assertThat(CodeDictionary.extract("<html><body>502 Bad Gateway</body></html>")).isNull();
        assertThat(CodeDictionary.extract("100503")).isNull();
        assertThat(CodeDictionary.extract("")).isNull();
        assertThat(CodeDictionary.extract(null)).isNull();
    }

    @Test
    @DisplayName("JSON 里没有返回码字段 → null（宁可说没识别到，也不猜）")
    void jsonWithoutCodeReturnsNull() {
        assertThat(CodeDictionary.extract("{\"msg\":\"ok\",\"total\":3}")).isNull();
    }

    @Test
    @DisplayName("嵌套超过上限就不再往下钻，避免异常结构递归过深")
    void stopsAtMaxDepth() {
        String deep = "{\"data\":{\"data\":{\"data\":{\"data\":{\"code\":7}}}}}}";
        assertThat(CodeDictionary.extract(deep)).isNull();
    }

    // ---------- 断言命中判断 ----------

    @Test
    @DisplayName("期望留空 → 一律算通过（不做这项校验）")
    void blankExpectAlwaysMatches() {
        assertThat(CodeDictionary.matches(null, "0")).isTrue();
        assertThat(CodeDictionary.matches("", "0")).isTrue();
        assertThat(CodeDictionary.matches("   ", null)).isTrue();
    }

    @Test
    @DisplayName("设了期望但实际值为 null → 不通过")
    void nullActualWithExpectFails() {
        assertThat(CodeDictionary.matches("0", null)).isFalse();
    }

    @Test
    @DisplayName("多个期望值命中任意一个即通过，中英文逗号都认")
    void multipleExpectValues() {
        assertThat(CodeDictionary.matches("0,100501", "100501")).isTrue();
        assertThat(CodeDictionary.matches("0,100501", "100502")).isFalse();
        assertThat(CodeDictionary.matches("0，100501", "100501")).isTrue();
    }

    @Test
    @DisplayName("期望里有多余空格也能命中")
    void toleratesSpaces() {
        assertThat(CodeDictionary.matches(" 0 , 100501 ", "100501")).isTrue();
        assertThat(CodeDictionary.matches("0", " 0 ")).isTrue();
    }

    @Test
    @DisplayName("字符串与数字混写能比上 —— \"0\"（引号里的）与 0 视为同一个")
    void numberAndStringCompareEqual() {
        // 抠出来的一律是字符串，界面上写的可能是数字，两边都按字符串比
        assertThat(CodeDictionary.matches("0", "0")).isTrue();
        assertThat(CodeDictionary.matches("200", "200")).isTrue();
        // 但不能把 0 和 00 混为一谈：那是两个不同的写法，宁可报不通过让人看见
        assertThat(CodeDictionary.matches("0", "00")).isFalse();
    }
}
