package internal.distilly.modules.system.controller;

import internal.distilly.config.PlatformProperties;
import internal.distilly.security.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class SystemController {

    private final UserAccountService accounts;

    private final PlatformProperties properties;

    public SystemController(UserAccountService accounts, PlatformProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(HttpServletRequest httpRequest) {
        String user = accounts.actorForHeader(httpRequest.getHeader("Authorization"));
        // Report token validation as form data so sign-in errors are handled by the page.
        Map<String, Object> result = user == null ? Map.of("error", "authentication_required")
                : Map.of("id", user, "demo", properties.isDemo(), "model_mode", properties.getModelMode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest httpRequest) {
        return Map.of("id", actor(httpRequest), "demo", properties.isDemo(), "model_mode", properties.getModelMode());
    }

}
