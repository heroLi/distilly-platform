package internal.distilly.security;

import internal.distilly.config.PlatformProperties;
import internal.distilly.security.service.UserAccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import static internal.distilly.common.support.Digests.sha256;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    private final UserAccountService accounts;

    private final PlatformProperties properties;

    public AuthenticationFilter(UserAccountService accounts, PlatformProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    public String actorForHeader(String header) {
        return accounts.actorForHeader(header);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; media-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'");
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") && !path.startsWith("/internal/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        if (path.startsWith("/internal/")) {
            if (!MessageDigest.isEqual(sha256(token).getBytes(StandardCharsets.US_ASCII),
                    sha256(properties.getWorkerToken()).getBytes(StandardCharsets.US_ASCII))) {
                deny(response);
                return;
            }
            request.setAttribute("actor", "worker");
        }
        else {
            String actor = actorForHeader(header);
            if (actor == null) {
                deny(response);
                return;
            }
            request.setAttribute("actor", actor);
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"authentication_required\"}");
    }

}
