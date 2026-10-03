package internal.distilly;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

@RestController
public class Api {
    private final PlatformService service;
    private final Auth auth;
    public Api(PlatformService service,Auth auth){this.service=service;this.auth=auth;}
    String actor(HttpServletRequest req){return (String)req.getAttribute("actor");}
    String field(Map<String,Object> body,String key,int max){return PlatformService.text(body.get(key),max);}
    @GetMapping("/health") Map<String,Object> health(){return Map.of("status","UP");}
    @GetMapping("/api/me") Map<String,Object> me(HttpServletRequest r){return Map.of("id",actor(r),"demo",auth.demo);}
    @GetMapping("/api/projects") Object projects(HttpServletRequest r){return service.projects(actor(r));}
    @PostMapping("/api/projects") Object project(HttpServletRequest r,@RequestBody Map<String,Object>b){return service.createProject(actor(r),field(b,"title",160));}
    @GetMapping("/api/projects/{p}/members") Object members(HttpServletRequest r,@PathVariable String p){return service.members(actor(r),p);}
    @PutMapping("/api/projects/{p}/members") void member(HttpServletRequest r,@PathVariable String p,@RequestBody Map<String,Object>b){service.member(actor(r),p,field(b,"user_id",100),field(b,"role",10));}
    @GetMapping("/api/projects/{p}/materials") Object materials(HttpServletRequest r,@PathVariable String p){return service.materials(actor(r),p);}
    @GetMapping("/api/materials/{m}") Object material(HttpServletRequest r,@PathVariable String m){return service.material(actor(r),m);}
    @PostMapping("/api/projects/{p}/materials") Object upload(HttpServletRequest r,@PathVariable String p,@RequestParam MultipartFile file)throws Exception{
        byte[] bytes=file.getBytes();if(bytes.length>1048576)throw PlatformService.error(413,"file_too_large");
        String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw PlatformService.error(422,"utf8_required");}
        if(text.indexOf('\0')>=0)throw PlatformService.error(422,"binary_content");return service.upload(actor(r),p,file.getOriginalFilename(),text);
    }
    @GetMapping("/api/projects/{p}/profiles") Object profiles(HttpServletRequest r,@PathVariable String p){return service.profiles(actor(r),p);}
    @GetMapping("/api/projects/{p}/archived-profiles") Object archived(HttpServletRequest r,@PathVariable String p){return service.archived(actor(r),p);}
    @DeleteMapping("/api/profiles/{f}") void deleteProfile(HttpServletRequest r,@PathVariable String f){service.deleteProfile(actor(r),f);}
    @PostMapping("/api/profiles/{f}/restore") void restoreProfile(HttpServletRequest r,@PathVariable String f){service.restoreProfile(actor(r),f);}
    @GetMapping("/api/projects/{p}/jobs") Object jobs(HttpServletRequest r,@PathVariable String p){return service.jobs(actor(r),p);}
    @PostMapping("/api/projects/{p}/profiles") Object profile(HttpServletRequest r,@PathVariable String p,@RequestBody Map<String,Object>b){return service.createProfile(actor(r),p,field(b,"title",160));}
    @GetMapping("/api/profiles/{f}/versions") Object versions(HttpServletRequest r,@PathVariable String f){return service.versions(actor(r),f);}
    @PostMapping("/api/profiles/{f}/distill") Object distill(HttpServletRequest r,@PathVariable String f,@RequestBody Map<String,Object>b){
        if(!(b.get("material_ids") instanceof List<?> ids) || ids.stream().anyMatch(x->!(x instanceof String)))throw PlatformService.error(422,"invalid_material_selection");
        return service.distill(actor(r),f,ids.stream().map(Object::toString).toList());
    }
    @PostMapping("/api/versions/{v}/review") void review(HttpServletRequest r,@PathVariable String v,@RequestBody Map<String,Object>b){if(!(b.get("approve") instanceof Boolean approved))throw PlatformService.error(422,"approve_required");service.review(actor(r),v,approved,(String)b.get("note"));}
    @PostMapping("/api/profiles/{f}/rollback") void rollback(HttpServletRequest r,@PathVariable String f,@RequestBody Map<String,Object>b){service.rollback(actor(r),f,field(b,"version_id",36));}
    @GetMapping("/api/profiles/{f}/download") ResponseEntity<String> download(HttpServletRequest r,@PathVariable String f){
        var version=service.published(actor(r),f);String markdown="---\nname: work-"+f+"\ndescription: \"审核发布的工作经验\"\nuser-invocable: true\n---\n\n"+version.get("work");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=SKILL.md").header("X-Profile-Version",(String)version.get("id")).contentType(new MediaType("text","markdown",StandardCharsets.UTF_8)).body(markdown);
    }
    @PostMapping("/api/profiles/{f}/questions") Object question(HttpServletRequest r,@PathVariable String f,@RequestBody Map<String,Object>b){return service.question(actor(r),f,field(b,"question",4000));}
    @GetMapping("/api/jobs/{j}") Object job(HttpServletRequest r,@PathVariable String j){return service.job(actor(r),j);}
    @PostMapping("/api/jobs/{j}/pause") void pause(HttpServletRequest r,@PathVariable String j){service.controlJob(actor(r),j,"pause");}
    @PostMapping("/api/jobs/{j}/resume") void resume(HttpServletRequest r,@PathVariable String j){service.controlJob(actor(r),j,"resume");}
    @PostMapping("/api/jobs/{j}/stop") void stop(HttpServletRequest r,@PathVariable String j){service.controlJob(actor(r),j,"stop");}
    @DeleteMapping("/api/jobs/{j}") void deleteJob(HttpServletRequest r,@PathVariable String j){service.deleteJob(actor(r),j);}
    @PostMapping("/api/jobs/{j}/restore") void restoreJob(HttpServletRequest r,@PathVariable String j){service.restoreJob(actor(r),j);}
    @GetMapping("/api/projects/{p}/archived-jobs") Object archivedJobs(HttpServletRequest r,@PathVariable String p){return service.archivedJobs(actor(r),p);}
    @PostMapping("/api/jobs/{j}/retry") void retry(HttpServletRequest r,@PathVariable String j){service.retry(actor(r),j);}
    @GetMapping("/api/projects/{p}/audit") Object audit(HttpServletRequest r,@PathVariable String p){return service.auditLog(actor(r),p);}
    @PostMapping("/internal/jobs/claim") ResponseEntity<?> claim(){var job=service.claim();return job==null?ResponseEntity.noContent().build():ResponseEntity.ok(job);}
    @PostMapping("/internal/jobs/{j}/heartbeat") void heartbeat(@PathVariable String j,@RequestBody Map<String,Object>b){service.heartbeat(j,field(b,"lease_token",36));}
    @PostMapping("/internal/jobs/{j}/progress") void progress(@PathVariable String j,@RequestBody Map<String,Object>b){if(!(b.get("completed") instanceof Integer completed))throw PlatformService.error(422,"invalid_progress");service.progress(j,field(b,"lease_token",36),completed);}
    @PostMapping("/internal/jobs/{j}/complete") Object complete(@PathVariable String j,@RequestBody Map<String,Object>b){if(!(b.get("output") instanceof Map<?,?>))throw PlatformService.error(422,"output_required");return service.complete(j,field(b,"lease_token",36),service.decode(service.encode(b.get("output"))));}
    @PostMapping("/internal/jobs/{j}/fail") void fail(@PathVariable String j,@RequestBody Map<String,Object>b){service.fail(j,field(b,"lease_token",36),field(b,"code",40));}

    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",Objects.requireNonNullElse(e.getReason(),"request_failed")));}
        @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> size(){return ResponseEntity.status(413).body(Map.of("error","file_too_large"));}
        @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,ClassCastException.class,IllegalArgumentException.class}) ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("error","invalid_request"));}
    }
}
