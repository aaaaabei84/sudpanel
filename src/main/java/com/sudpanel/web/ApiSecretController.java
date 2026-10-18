package com.sudpanel.web;

import com.sudpanel.api.ApiSecretStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apitest/secrets")
public class ApiSecretController {
    private final ApiSecretStore store;
    public ApiSecretController(JdbcTemplate jdbc){store=new ApiSecretStore(jdbc);}
    @GetMapping public java.util.List<ApiSecretStore.Summary> list(@RequestParam long envId,HttpServletRequest request){local(request);return store.list(envId);}
    @PostMapping public ApiSecretStore.Summary create(@RequestParam long envId,@RequestBody ApiSecretStore.Input input,HttpServletRequest request){local(request);return store.create(envId,input);}
    public record Enabled(boolean enabled){}
    @PostMapping("/{id}/enabled") public java.util.Map<String,Boolean> enabled(@PathVariable String id,@RequestParam long envId,@RequestBody Enabled input,HttpServletRequest request){local(request);store.enabled(envId,id,input.enabled());return java.util.Map.of("ok",true);}
    private static void local(HttpServletRequest request){try{if(java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())return;}catch(Exception ignored){}throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"秘密引用仅允许本机管理");}
}
