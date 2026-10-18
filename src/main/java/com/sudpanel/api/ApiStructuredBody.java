package com.sudpanel.api;

/** 结构树预算独立于下载字节限制；只限制响应解析，不截断或改写原始归档。 */
public final class ApiStructuredBody {
    private ApiStructuredBody() {}
    public static final int MAX_CHARS=2*1024*1024;
    public static final String MESSAGE="响应体超过结构化解析上限 2097152 字符（UTF-16）；未执行正文/JSON解析，请使用响应文件、响应头或耗时检查";
    public static boolean overLimit(String body){return body!=null&&body.length()>MAX_CHARS;}
    public static void require(String body){if(overLimit(body))throw new IllegalArgumentException(MESSAGE);}
}
