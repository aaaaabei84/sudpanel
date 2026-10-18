package com.sudpanel.web;
import com.sudpanel.api.ApiWorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apitest/workspaces")
public class ApiWorkspaceController {
    private final ApiWorkspaceRepository repository;
    public ApiWorkspaceController(ApiWorkspaceRepository repository) { this.repository = repository; }
    public record Save(Long expectedRevision, String content) {}
    @GetMapping("/{id}") public ApiWorkspaceRepository.Workspace read(@PathVariable String id, HttpServletRequest request) {
        local(request); return repository.read(id);
    }
    @PutMapping("/{id}") public ApiWorkspaceRepository.Workspace save(@PathVariable String id, @RequestBody Save value, HttpServletRequest request) {
        local(request); if (value.expectedRevision() == null) throw new IllegalArgumentException("缺少草稿版本");
        return repository.save(id, value.expectedRevision(), value.content());
    }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id, @RequestParam long expectedRevision, HttpServletRequest request) {
        local(request); repository.delete(id, expectedRevision);
    }
    private static void local(HttpServletRequest request) {
        try { if (java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return; }
        catch (Exception ignored) { }
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "草稿仅允许本机访问");
    }
}
