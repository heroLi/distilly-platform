package internal.distilly;

import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.memory.domain.MemoryProvider;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static internal.distilly.common.validation.InputChecks.error;

/**
 * Test-classpath-only structured fixture projection, not a Spring component or product API.
 * Explicit fixture booleans are axioms, never extracted or proved from memory text.
 * Real SQL membership/ownership/confirmation/source identity fence their use; a monotonic
 * sync revision, not a matching content hash alone, prevents correction A-B-A revival.
 * Reads use the existing membership -> persona authorized-snapshot lock order. They do
 * not promise HTTP-send-time revocation. Source/memory mutations use actual services
 * under explicit test transactions; no business migration installs this table.
 */
final class SyntheticLampSqlExperiment {
    enum Status { DISABLED, UNKNOWN, SUPPORTED, REFUTED, CONFLICT }
    record Fact(String lamp, boolean value, long from, long until) { }
    record Evidence(String factId, String memoryId, String sourceId, String sourceVersion,
                    long revision, String operation) { }
    record Decision(Status status, List<Evidence> evidence) { }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PersonaAccessService access;
    private final boolean enabled;
    private static final Set<String> LAMPS=Set.of("synthetic-lamp-a","synthetic-lamp-b");
    private static final long MAX_TIME=4102444800L;
    SyntheticLampSqlExperiment(JdbcTemplate db, PlatformTransactionManager manager, PersonaAccessService access) {
        this(db,manager,access,false);
    }
    SyntheticLampSqlExperiment(JdbcTemplate db, PlatformTransactionManager manager, PersonaAccessService access, boolean enabled) {
        this.db=db;this.access=access;this.enabled=enabled;this.tx=enabled?new TransactionTemplate(manager):null;
    }
    static void installFixtureTable(JdbcTemplate db) {
        String url=db.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getURL());
        if(url==null||!url.startsWith("jdbc:h2:file:")||!url.endsWith("/synthetic-lamp-ledger"))
            throw new IllegalArgumentException("only the dedicated synthetic H2 fixture");
        db.execute("CREATE TABLE synthetic_lamp_fact (id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, persona_id VARCHAR(36) NOT NULL, user_id VARCHAR(100) NOT NULL, memory_id VARCHAR(36) NOT NULL, source_id VARCHAR(36) NOT NULL, source_version VARCHAR(36) NOT NULL, sync_revision BIGINT NOT NULL, sync_operation VARCHAR(36) NOT NULL, fingerprint VARCHAR(64) NOT NULL, lamp VARCHAR(32) NOT NULL, on_value BOOLEAN NOT NULL, valid_from BIGINT NOT NULL, valid_until BIGINT NOT NULL, confirmation VARCHAR(12) NOT NULL)");
    }
    private void opened() { if(!enabled)throw error(409,"synthetic_experiment_disabled"); }
    private Map<String,Object> guard(String actor,String persona) {
        var initial=access.persona(actor,persona);
        if(db.queryForList("SELECT role FROM member WHERE user_id=? AND project_id=? FOR UPDATE",actor,initial.get("project_id")).isEmpty())
            throw error(403,"project_access_denied");
        access.lock("persona",persona);return access.persona(actor,persona);
    }
    private Map<String,Object> source(String actor,String persona,Object project,String memory) {
        var rows=db.queryForList("SELECT m.id,m.body,m.source_id,c.version_id AS source_version,i.revision AS sync_revision,i.operation_id AS sync_operation,i.fingerprint AS sync_fingerprint FROM persona_memory m JOIN persona_message s ON s.id=m.source_id JOIN persona_conversation c ON c.id=s.conversation_id JOIN memory_sync_intent i ON i.memory_id=m.id AND i.persona_id=m.persona_id AND i.user_id=m.user_id WHERE m.id=? AND m.persona_id=? AND m.user_id=? AND m.status='CONFIRMED' AND s.role='user' AND c.persona_id=m.persona_id AND c.user_id=m.user_id AND i.project_id=? AND i.kind='RETAIN' AND i.source_id=m.source_id AND NOT EXISTS(SELECT 1 FROM memory_sync_intent n WHERE n.memory_id=i.memory_id AND n.persona_id=i.persona_id AND n.user_id=i.user_id AND n.revision>i.revision)",memory,persona,actor,project);
        if(rows.size()!=1)return null;
        var row=rows.get(0);
        return MemoryProvider.reference(row).revision().equals(row.get("sync_fingerprint"))?row:null;
    }
    private boolean same(Map<String,Object> fact,Map<String,Object> source) {
        return source!=null&&Objects.equals(fact.get("source_id"),source.get("source_id"))
            &&Objects.equals(fact.get("source_version"),source.get("source_version"))
            &&Objects.equals(fact.get("sync_operation"),source.get("sync_operation"))
            &&((Number)fact.get("sync_revision")).longValue()==((Number)source.get("sync_revision")).longValue()
            &&Objects.equals(fact.get("fingerprint"),source.get("sync_fingerprint"));
    }
    private Map<String,Object> owned(String actor,String persona,Object project,String id) {
        var rows=db.queryForList("SELECT * FROM synthetic_lamp_fact WHERE id=? AND persona_id=? AND user_id=? AND project_id=?",id,persona,actor,project);
        if(rows.size()!=1)throw error(404,"synthetic_fact_not_found");return rows.get(0);
    }
    String propose(String actor, String persona, String memory, Fact fact) {
        opened();if(fact==null||fact.lamp()==null||!LAMPS.contains(fact.lamp())||fact.from()<0||fact.from()>=fact.until()||fact.until()>MAX_TIME)
            throw error(422,"invalid_synthetic_fact");
        return tx.execute(s->{
            var project=guard(actor,persona).get("project_id");var source=source(actor,persona,project,memory);
            if(source==null)throw error(409,"synthetic_source_changed");
            if(db.queryForObject("SELECT COUNT(*) FROM synthetic_lamp_fact WHERE persona_id=? AND user_id=?",Integer.class,persona,actor)>=128)
                throw error(409,"synthetic_fact_limit");
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO synthetic_lamp_fact VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",id,project,persona,actor,memory,
                source.get("source_id"),source.get("source_version"),source.get("sync_revision"),source.get("sync_operation"),
                source.get("sync_fingerprint"),fact.lamp(),fact.value(),fact.from(),fact.until());return id;
        });
    }
    void confirm(String actor, String persona, String id) {
        opened();tx.executeWithoutResult(s->{
            var project=guard(actor,persona).get("project_id");var fact=owned(actor,persona,project,id);
            if("REVOKED".equals(fact.get("confirmation"))||!same(fact,source(actor,persona,project,fact.get("memory_id").toString())))
                throw error(409,"synthetic_source_changed");
            db.update("UPDATE synthetic_lamp_fact SET confirmation='CONFIRMED' WHERE id=?",id);
        });
    }
    void revoke(String actor, String persona, String id) {
        opened();tx.executeWithoutResult(s->{var project=guard(actor,persona).get("project_id");owned(actor,persona,project,id);
            db.update("UPDATE synthetic_lamp_fact SET confirmation='REVOKED' WHERE id=?",id);});
    }
    Decision decide(String actor, String persona, String lamp, boolean expected, long at) {
        if(!enabled)return new Decision(Status.DISABLED,List.of());
        if(lamp==null||at<0||at>MAX_TIME)throw error(422,"invalid_synthetic_query");
        return tx.execute(s->{
            var project=guard(actor,persona).get("project_id");
            if(!LAMPS.contains(lamp))return new Decision(Status.UNKNOWN,List.of());
            var rows=db.queryForList("SELECT * FROM synthetic_lamp_fact WHERE project_id=? AND persona_id=? AND user_id=? AND lamp=? AND confirmation='CONFIRMED' AND valid_from<=? AND valid_until>? ORDER BY id",project,persona,actor,lamp,at,at);
            if(rows.size()>128)throw error(409,"synthetic_fact_limit");
            var values=new HashSet<Boolean>();var evidence=new ArrayList<Evidence>();
            for(var fact:rows)if(same(fact,source(actor,persona,project,fact.get("memory_id").toString()))){
                values.add((Boolean)fact.get("on_value"));evidence.add(new Evidence(fact.get("id").toString(),fact.get("memory_id").toString(),fact.get("source_id").toString(),fact.get("source_version").toString(),((Number)fact.get("sync_revision")).longValue(),fact.get("sync_operation").toString()));
            }
            Status status=values.isEmpty()?Status.UNKNOWN:values.size()>1?Status.CONFLICT:
                values.contains(expected)?Status.SUPPORTED:Status.REFUTED;
            return new Decision(status,List.copyOf(evidence));
        });
    }
}
