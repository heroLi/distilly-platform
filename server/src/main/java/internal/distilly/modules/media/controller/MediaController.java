package internal.distilly.modules.media.controller;

import internal.distilly.modules.media.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;
import static internal.distilly.web.support.RequestContext.str;

@RestController
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PutMapping("/api/personas/{id}/avatar")
    public void avatar(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        mediaService.setAvatar(actor(httpRequest), id, body.get("avatar"));
    }

    @PostMapping("/api/personas/{id}/image")
    public Map<String, Object> image(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return mediaService.generateImage(actor(httpRequest), id, str(body, "prompt", 2000));
    }

    @PostMapping("/api/personas/{id}/speech")
    public Map<String, Object> speech(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return mediaService.media(actor(httpRequest), id, body);
    }

}
