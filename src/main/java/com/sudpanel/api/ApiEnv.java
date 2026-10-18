package com.sudpanel.api;

/**
 * 一个环境（2026-10-10，v1.10.7 需求 #87）。
 *
 * <p>一个环境 = 一套变量（请求头/请求体/基址三组）+ 一套签名配置，两者共用这个名字。
 * 用例分组通过 {@code envId} 引用它；没绑定的用默认环境（id=1）。
 *
 * @param id        环境 id；1 固定为默认环境
 * @param name      环境名（唯一）
 * @param isDefault 是否默认环境（全库只允许一个，是未绑定用例的兜底）
 * @param sortOrder 排序（环境卡片按它排）
 */
public record ApiEnv(long id, String name, boolean isDefault, Integer sortOrder, Boolean strictVariables) {
    public ApiEnv(long id, String name, boolean isDefault, Integer sortOrder) { this(id, name, isDefault, sortOrder, null); }
}
