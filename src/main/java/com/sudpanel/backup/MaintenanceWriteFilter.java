package com.sudpanel.backup;
import java.io.IOException;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** GET/状态读取不拦；stage/撤销独立写锁，避免与本过滤器读锁升级死锁。 */
@Component @Order(20)
public class MaintenanceWriteFilter extends OncePerRequestFilter {
    private final MaintenanceGate gate;private final MaintenanceService service;
    public MaintenanceWriteFilter(MaintenanceGate gate,MaintenanceService service){this.gate=gate;this.service=service;}
    private static boolean isLocal(String address){try{return java.net.InetAddress.getByName(address).isLoopbackAddress();}catch(Exception e){return false;}}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse resp,FilterChain chain)throws ServletException,IOException{
        String path=req.getRequestURI();
        if((path.startsWith("/api/maintenance/")||path.startsWith("/api/apitest/files"))&&!isLocal(req.getRemoteAddr())){resp.setStatus(403);resp.setContentType("application/json;charset=UTF-8");resp.getWriter().write("{\"message\":\"数据维护与受管文件仅允许本机访问\"}");return;}
        boolean bypass=Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())||!path.startsWith("/api/")
                ||path.startsWith("/api/maintenance/transfers/")||path.matches("/api/maintenance/restores/(?:[a-f0-9-]{36}/stage|stage|preview)")||path.equals("/api/maintenance/backups")||path.equals("/api/maintenance/cleanup/commit")||path.endsWith("/stop")||path.endsWith("/cancel");
        if(bypass){chain.doFilter(req,resp);return;}
        gate.lock.readLock().lock();try{if(service.staged()){resp.setStatus(409);resp.setContentType("application/json;charset=UTF-8");resp.getWriter().write("{\"message\":\"已安排数据库恢复，请重启完成或先撤销恢复stage\",\"status\":409}");return;}chain.doFilter(req,resp);}finally{gate.lock.readLock().unlock();}
    }
}
