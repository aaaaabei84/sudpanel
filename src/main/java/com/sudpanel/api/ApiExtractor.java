package com.sudpanel.api;

/** name 是 run. 后面的名字；JSON 路径支持既有点号/下标或 JSON Pointer。 */
public record ApiExtractor(String name, String source, String path, Boolean required, Boolean secret) {
    public ApiExtractor(String name,String source,String path,Boolean required){this(name,source,path,required,false);}
}
