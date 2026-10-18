package com.sudpanel.api;

import java.util.List;

/** 可选请求设置。null 保留旧用例的请求体和环境鉴权行为。 */
public record ApiRequestOptions(String bodyType, List<Param> params, Auth auth,
                                List<ApiExtractor> extractors, List<Param> fields,
                                Transport transport,Boolean strictVariables,FileBody fileBody,List<FilePart> parts,List<HeaderRow> headerRows) {
    public ApiRequestOptions(String bodyType,List<Param> params,Auth auth,List<ApiExtractor> extractors,List<Param> fields,Transport transport,Boolean strictVariables){this(bodyType,params,auth,extractors,fields,transport,strictVariables,null,null,null);}
    public ApiRequestOptions(String bodyType,List<Param> params,Auth auth,List<ApiExtractor> extractors,List<Param> fields,Transport transport,Boolean strictVariables,FileBody fileBody,List<FilePart> parts){this(bodyType,params,auth,extractors,fields,transport,strictVariables,fileBody,parts,null);}
    public ApiRequestOptions(String bodyType,List<Param> params,Auth auth,List<ApiExtractor> extractors,List<Param> fields,Transport transport,Boolean strictVariables,List<HeaderRow> headerRows){this(bodyType,params,auth,extractors,fields,transport,strictVariables,null,null,headerRows);}
    public ApiRequestOptions(String bodyType, List<Param> params, Auth auth, List<ApiExtractor> extractors) {
        this(bodyType, params, auth, extractors, null, null, null);
    }
    public record FileBody(String fileId,String filename,String contentType) {}
    public record FilePart(String name,String type,String value,String fileId,String filename,String contentType,Boolean enabled) {}
    public record Param(String name, String value, Boolean enabled) {}
    public record HeaderRow(String name,String value,Boolean enabled) {}
    /** 旧 null 延续原客户端行为；响应上限同时约束传输字节与解压字节。 */
    public record Transport(String redirect, String proxyMode, String proxyHost, Integer proxyPort,
                            String cookieMode, Integer maxResponseBytes,Integer maxRedirects,String httpVersion,
                            String cookieSession,String cookieDataRows,String certificateProfileId) {
        public Transport(String redirect,String proxyMode,String proxyHost,Integer proxyPort,String cookieMode,Integer maxResponseBytes) {
            this(redirect,proxyMode,proxyHost,proxyPort,cookieMode,maxResponseBytes,null,null,null,null,null);
        }
    }
    public record Auth(String type, String token, String username, String password,
                       String keyName, String keyValue, String in,String profileId,String digestAlgorithm,String digestQop,
                       String script,String authorizationTemplate) {
        public Auth(String type,String token,String username,String password,String keyName,String keyValue,String in,String profileId,String digestAlgorithm,String digestQop) {
            this(type,token,username,password,keyName,keyValue,in,profileId,digestAlgorithm,digestQop,null,null);
        }
        public Auth(String type,String token,String username,String password,String keyName,String keyValue,String in) {
            this(type,token,username,password,keyName,keyValue,in,null,null,null);
        }
    }
}
