package internal.distilly.modules.media.controller;

import internal.distilly.modules.media.service.VoiceProfileService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class VoiceProfileController {
    private final VoiceProfileService service;
    public VoiceProfileController(VoiceProfileService service) { this.service=service; }
    @GetMapping("/api/personas/{id}/voice-profiles")
    public List<Map<String,Object>> list(HttpServletRequest r,@PathVariable("id") String id) { return service.list(actor(r),id); }
    @PostMapping("/api/personas/{id}/voice-profiles")
    public Map<String,Object> create(HttpServletRequest r,@PathVariable("id") String id,@RequestBody Map<String,Object> body) { return service.create(actor(r),id,body); }
    @GetMapping("/api/personas/{id}/voice-profiles/{v}")
    public Map<String,Object> get(HttpServletRequest r,@PathVariable("id") String id,@PathVariable("v") String v) { return service.get(actor(r),id,v); }
    @PostMapping("/api/personas/{id}/voice-profiles/{v}/preview")
    public Map<String,Object> preview(HttpServletRequest r,@PathVariable("id") String id,@PathVariable("v") String v,@RequestBody Map<String,Object> body) { return service.preview(actor(r),id,v,body); }
    @PostMapping("/api/personas/{id}/voice-profiles/{v}/revoke")
    public void revoke(HttpServletRequest r,@PathVariable("id") String id,@PathVariable("v") String v) { service.close(actor(r),id,v,false); }
    @DeleteMapping("/api/personas/{id}/voice-profiles/{v}")
    public void delete(HttpServletRequest r,@PathVariable("id") String id,@PathVariable("v") String v) { service.close(actor(r),id,v,true); }
}
