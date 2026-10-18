package com.sudpanel.web;
import com.sudpanel.api.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.net.InetAddress;
import java.util.*;

/** profile与Cookie管理仅本机开放；列表始终遮罩，证书及密码不设下载接口。 */
@RestController @RequestMapping("/api/apitest/advanced")
public class ApiHttpAdvancedController {
    private final ApiHttpProfiles profiles;private final ApiRunner runner;
    public ApiHttpAdvancedController(ApiHttpProfiles profiles,ApiRunner runner){this.profiles=profiles;this.runner=runner;}
    private void local(HttpServletRequest request){try{if(!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())throw new IllegalArgumentException();}catch(Exception e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"高级会话/秘密profile仅允许本机管理");}}
    @GetMapping("/envs/{env}/profiles") public List<Map<String,Object>> list(@PathVariable long env,HttpServletRequest r){local(r);return profiles.list(env);}
    @PostMapping("/envs/{env}/profiles") public Map<String,Object> create(@PathVariable long env,@RequestBody ApiHttpProfiles.Input input,HttpServletRequest r){local(r);return profiles.create(env,input);}
    @DeleteMapping("/envs/{env}/profiles/{id}") public Map<String,Boolean> delete(@PathVariable long env,@PathVariable String id,HttpServletRequest r){local(r);profiles.delete(env,id);return Map.of("ok",true);}
    @DeleteMapping("/envs/{env}/tokens") public Map<String,Boolean> tokens(@PathVariable long env,HttpServletRequest r){local(r);profiles.clearTokens(env);return Map.of("ok",true);}
    @GetMapping("/envs/{env}/cookies") public List<Map<String,Object>> cookies(@PathVariable long env,@RequestParam(required=false) String scope,HttpServletRequest r){local(r);return runner.cookies(env,scope);}
    @DeleteMapping("/envs/{env}/cookies") public Map<String,Boolean> clear(@PathVariable long env,@RequestParam(required=false) String scope,@RequestParam(required=false) String id,HttpServletRequest r){local(r);runner.deleteCookie(env,scope,id);return Map.of("ok",true);}
}
