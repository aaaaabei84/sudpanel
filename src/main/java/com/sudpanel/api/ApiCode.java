package com.sudpanel.api;

/**
 * 返回码字典里的一条。
 *
 * @param id         自增 id
 * @param scope      分类：CLIENT / IOS / SDK / GAME / CUSTOM
 * @param scopeLabel 分类中文名
 * @param code       返回码数值
 * @param message    含义说明
 * @param source     来源：SEED（官方公开文档）/ CUSTOM（自己补充）
 */
public record ApiCode(
        Long id,
        String scope,
        String scopeLabel,
        long code,
        String message,
        String source
) {
    /** 分类中文名。字典里存了一份，这里主要用于新增时兜底。 */
    public static String labelOf(String scope) {
        return switch (scope == null ? "" : scope) {
            case "CLIENT" -> "客户端错误码";
            case "IOS" -> "iOS 系统错误码";
            case "SDK" -> "服务端 SDK 错误码";
            case "GAME" -> "游戏服务端错误码";
            case "CUSTOM" -> "自定义（内部补充）";
            default -> scope == null ? "" : scope;
        };
    }
}
