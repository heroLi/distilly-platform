package internal.distilly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class Auth extends OncePerRequestFilter {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    @Value("${platform.demo}") boolean demo;
    @Value("${platform.users}") String users;
    @Value("${platform.worker-token}") String worker;
    public Auth(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("digest unavailable"); }
    }
    @PostConstruct void configureUsers() {
        if (worker.length()<32) throw new IllegalStateException("PLATFORM_WORKER_TOKEN must contain at least 32 characters");
        List<Map<String,String>> configured;
        try { configured=json.readValue(users,new TypeReference<>(){}); }
        catch(Exception e) { throw new IllegalStateException("PLATFORM_USERS must be valid JSON"); }
        if(demo) configured=List.of(Map.of("id","owner","token","demo-owner"),Map.of("id","editor","token","demo-editor"),Map.of("id","viewer","token","demo-viewer"));
        if(configured.isEmpty()) throw new IllegalStateException("Configure PLATFORM_USERS or explicitly enable PLATFORM_DEMO");
        Set<String> activeUsers=new HashSet<>();
        for(var u:configured) {
            String id=u.get("id"), token=u.get("token");
            if(id==null || !id.matches("[a-zA-Z0-9_-]{1,100}") || token==null || (!demo && token.length()<32)) throw new IllegalStateException("invalid configured user");
            if(!activeUsers.add(id)) throw new IllegalStateException("duplicate configured user");
            if(db.update("UPDATE app_user SET token_hash=? WHERE id=?",hash(token),id)==0) db.update("INSERT INTO app_user VALUES (?,?)",id,hash(token));
        }
        // Leaving demo mode or removing an account must revoke its old token.
        // Keep the row so historical audit and membership references survive.
        for(String previous:db.queryForList("SELECT id FROM app_user",String.class)) {
            if(!activeUsers.contains(previous)) db.update("UPDATE app_user SET token_hash=? WHERE id=?",hash("revoked:"+UUID.randomUUID()),previous);
        }
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'");
        String path=request.getRequestURI();
        if(!path.startsWith("/api/") && !path.startsWith("/internal/")) {chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");
        String header=request.getHeader("Authorization");
        String token=header!=null && header.startsWith("Bearer ")?header.substring(7):"";
        if(path.startsWith("/internal/")) {
            if(!MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),hash(worker).getBytes(StandardCharsets.US_ASCII))) {deny(response);return;}
            request.setAttribute("actor","worker");
        } else {
            var ids=db.queryForList("SELECT id FROM app_user WHERE token_hash=?",String.class,hash(token));
            if(ids.isEmpty()) {deny(response);return;}
            request.setAttribute("actor",ids.get(0));
        }
        chain.doFilter(request,response);
    }
    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"error\":\"authentication_required\"}");
    }
}
