package com.sudpanel.api;

/**
 * 用例分组（2026-10-04 新增，需求 #48）。
 *
 * <p><b>为什么是独立的表而不是用例上的一个文本字段。</b>用户在界面上要能「重命名分组」——
 * 如果是用例上的一个 {@code group_name} 文本列，改名就得更新那一组所有用例，
 * 改到一半失败会留下两组名字（一半旧一半新），而界面上看起来像「多出来一个分组」。
 * 独立成表之后改名只动一行，全组瞬间跟着变；顺带还能支持「先建好一个空分组」。
 *
 * <p>删除分组<b>不删用例</b>：组里的用例会被置为「未分组」。用例是用户自己填了半天的资产，
 * 而分组只是归类方式 —— 删一个归类不该让人丢东西。
 *
 * @param id        分组 id
 * @param name      分组名称（唯一，界面上按它排序展示）
 * @param sortOrder 展示顺序
 * @param caseCount 组内用例数
 * @param envId      绑定的环境；null = 未绑定，执行时用默认环境 该组的用例数（仅列表接口返回，用于在分组卡片上显示计数）
 */
public record ApiCaseGroup(Long id, String name, Integer sortOrder, Integer caseCount, Long envId, String importNote, java.util.List<String> importPath) {
    public ApiCaseGroup(Long id,String name,Integer sortOrder,Integer caseCount,Long envId){this(id,name,sortOrder,caseCount,envId,null,null);}
    public ApiCaseGroup { importPath=importPath==null?null:java.util.List.copyOf(importPath); }

    /** 未分组在界面上的名字。它不是一个真实分组，没有 id。 */
    public static final String UNGROUPED_LABEL = "未分组";
}
