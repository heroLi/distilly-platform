package internal.distilly.infrastructure.migration;

import internal.distilly.common.support.Digests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Narrow recovery of the reviewed V005 ordered DDL prefix, never a generic ignore-errors runner. */
final class H2V005Recovery {
    static final String SCRIPT_SHA256="2d810dfa1c52291942265a5542513657fee606c0b68fcd80623ee7b7a4799f4d";
    private final JdbcTemplate db;
    private final List<String> statements;
    private final Context context;
    private record Context(String catalog,String schema,boolean upper,boolean lower) {
        String name(String value){return upper?value.toUpperCase(Locale.ROOT):lower?value.toLowerCase(Locale.ROOT):value;}
    }
    private record Column(String name,String type,Long length,boolean nullable,String defaultSql) { }
    private record Shape(String name,String type,Long length,String nullable,String defaultSql,String identity,String generated,
                         String domain,String onUpdate,boolean defaultOnNull,boolean visible,int ordinal) { }
    private static Column varchar(String name,long length,boolean nullable){return new Column(name,"CHARACTER VARYING",length,nullable,null);}
    private static Column bigint(String name){return new Column(name,"BIGINT",null,false,null);}
    // H2 2.3.232 maps the original TEXT spelling to VARCHAR(1000000000), not CLOB.
    private static Column text(String name,boolean nullable){return varchar(name,1000000000L,nullable);}
    private static Column bool(String name){return new Column(name,"BOOLEAN",null,false,"FALSE");}
    private static final List<Column> PERSONA=List.of(varchar("id",36,false),varchar("project_id",36,false),varchar("name",100,false),
            varchar("family",16,false),text("definition",false),varchar("published_id",36,true),text("avatar",true),bool("deleted"),
            varchar("creator",100,false),bigint("created_at"));
    private static final List<Column> VERSION=List.of(varchar("id",36,false),varchar("persona_id",36,false),varchar("base_id",36,true),
            varchar("status",12,false),text("content",false),varchar("creator",100,false),bigint("created_at"));
    private static final List<Column> HISTORY=List.of(new Column("version","INTEGER",null,false,null),bigint("applied_at"));

    H2V005Recovery(JdbcTemplate db,Resource source) {
        this.db=db;
        try(var input=source.getInputStream()) {
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            if(!SCRIPT_SHA256.equals(Digests.sha256(sql)))throw failure("script_checksum_mismatch");
            statements=Arrays.stream(sql.split(";")).map(String::trim).filter(s->!s.isEmpty()).toList();
            if(statements.size()!=3)throw failure("script_checksum_mismatch");
        }catch(IOException unavailable){throw failure("script_unavailable");}
        context=db.execute((ConnectionCallback<Context>) connection->{
            var metadata=connection.getMetaData();
            if(!"H2".equals(metadata.getDatabaseProductName())||connection.getSchema()==null)throw failure("unsupported_context");
            return new Context(connection.getCatalog(),connection.getSchema(),metadata.storesUpperCaseIdentifiers(),metadata.storesLowerCaseIdentifiers());
        });
    }
    private static IllegalStateException failure(String reason){return new IllegalStateException("migration_v005_"+reason);}

    /** Runs before other pending migrations: an unknown history/schema is not automatically repaired. */
    void preflight(int supportedVersions) {
        int latest=history(supportedVersions);
        if(latest>=5)state(true);
        else if(latest==4)state(false);
        else if(columns("persona").containsKey(context.name("library"))
                ||columns("persona_version").keySet().stream().anyMatch(k->Set.of(context.name("source_personality_id"),context.name("source_personality_version_id")).contains(k)))
            throw failure("history_mismatch");
    }
    void resume(int supportedVersions) {
        if(history(supportedVersions)!=4)throw failure("history_mismatch");
        int prefix=state(false);
        // H2 DDL commits independently of the surrounding transaction. Revalidate its complete
        // postcondition before the caller records version5; do not catch or ignore SQL failures.
        for(int i=prefix;i<statements.size();i++)db.execute(statements.get(i));
        state(true);
    }
    private int history(int supportedVersions) {
        verifyTable("persona_schema_version",HISTORY,"version",null,null);
        var versions=db.queryForList("SELECT version FROM persona_schema_version ORDER BY version",Integer.class);
        if(versions.size()>supportedVersions)throw failure("history_mismatch");
        for(int i=0;i<versions.size();i++)if(versions.get(i)!=i+1)throw failure("history_mismatch");
        return versions.size();
    }
    private int state(boolean completed) {
        var people=columns("persona");var versions=columns("persona_version");
        boolean first=people.containsKey(context.name("library")),second=versions.containsKey(context.name("source_personality_id")),third=versions.containsKey(context.name("source_personality_version_id"));
        if((second&&!first)||(third&&!second))throw failure("non_prefix_schema");
        int prefix=first?(second?(third?3:2):1):0;
        if(completed&&prefix!=3)throw failure("completed_schema_mismatch");
        var expectedPeople=new ArrayList<>(PERSONA);var expectedVersions=new ArrayList<>(VERSION);
        if(first)expectedPeople.add(bool("library"));
        if(second)expectedVersions.add(varchar("source_personality_id",36,true));
        if(third)expectedVersions.add(varchar("source_personality_version_id",36,true));
        verifyTable("persona",expectedPeople,"id","project_id","project");
        verifyTable("persona_version",expectedVersions,"id","persona_id","persona");
        return prefix;
    }
    private Map<String,Shape> columns(String table) {
        var rows=db.query("SELECT COLUMN_NAME,DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,IS_NULLABLE,COLUMN_DEFAULT,IS_IDENTITY,IS_GENERATED,DOMAIN_NAME,COLUMN_ON_UPDATE,DEFAULT_ON_NULL,IS_VISIBLE,ORDINAL_POSITION FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
                (rs,row)->{
                    long length=rs.getLong("CHARACTER_MAXIMUM_LENGTH");Long nullableLength=rs.wasNull()?null:length;
                    return new Shape(rs.getString("COLUMN_NAME"),rs.getString("DATA_TYPE").toUpperCase(Locale.ROOT),nullableLength,rs.getString("IS_NULLABLE"),rs.getString("COLUMN_DEFAULT"),
                            rs.getString("IS_IDENTITY"),rs.getString("IS_GENERATED"),rs.getString("DOMAIN_NAME"),rs.getString("COLUMN_ON_UPDATE"),rs.getBoolean("DEFAULT_ON_NULL"),rs.getBoolean("IS_VISIBLE"),rs.getInt("ORDINAL_POSITION"));
                },context.schema(),context.name(table));
        var result=new LinkedHashMap<String,Shape>();for(var row:rows)result.put(row.name(),row);return result;
    }
    private void verifyTable(String table,List<Column> expected,String primary,String foreignColumn,String foreignTable) {
        var types=db.queryForList("SELECT TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",String.class,context.schema(),context.name(table));
        if(!types.equals(List.of("BASE TABLE")))throw failure("table_mismatch");
        var actual=columns(table);
        if(actual.size()!=expected.size())throw failure("column_mismatch");
        for(int i=0;i<expected.size();i++) {
            var column=expected.get(i);String name=context.name(column.name());
            var shape=new Shape(name,column.type(),column.length(),column.nullable()?"YES":"NO",column.defaultSql(),"NO","NEVER",null,null,false,true,i+1);
            if(!shape.equals(actual.get(name)))throw failure("column_mismatch");
        }
        if(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=?",Integer.class,context.schema(),context.name(table))!=0)throw failure("constraint_mismatch");
        var constraints=db.query("SELECT CONSTRAINT_TYPE,ENFORCED FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY CONSTRAINT_TYPE",
                (rs,row)->rs.getString("CONSTRAINT_TYPE")+":"+rs.getString("ENFORCED"),context.schema(),context.name(table));
        var expectedConstraints=foreignColumn==null?List.of("PRIMARY KEY:YES"):List.of("FOREIGN KEY:YES","PRIMARY KEY:YES");
        if(!constraints.equals(expectedConstraints))throw failure("constraint_mismatch");
        db.execute((ConnectionCallback<Void>) connection->{
            var metadata=connection.getMetaData();var keys=new ArrayList<String>();
            try(var rows=metadata.getPrimaryKeys(context.catalog(),context.schema(),context.name(table))){while(rows.next())keys.add(rows.getString("COLUMN_NAME"));}
            if(!keys.equals(List.of(context.name(primary))))throw failure("constraint_mismatch");
            var foreign=new ArrayList<String>();
            try(var rows=metadata.getImportedKeys(context.catalog(),context.schema(),context.name(table))){
                while(rows.next())foreign.add(rows.getString("FKCOLUMN_NAME")+":"+rows.getString("PKTABLE_CAT")+":"+rows.getString("PKTABLE_SCHEM")+":"+rows.getString("PKTABLE_NAME")+":"+rows.getString("PKCOLUMN_NAME")+":"+rows.getShort("KEY_SEQ")+":"+rows.getShort("UPDATE_RULE")+":"+rows.getShort("DELETE_RULE")+":"+rows.getShort("DEFERRABILITY"));
            }
            var expectedForeign=foreignColumn==null?List.<String>of():List.of(context.name(foreignColumn)+":"+context.catalog()+":"+context.schema()+":"+context.name(foreignTable)+":"+context.name("id")+":1:"+DatabaseMetaData.importedKeyRestrict+":"+DatabaseMetaData.importedKeyRestrict+":"+DatabaseMetaData.importedKeyNotDeferrable);
            if(!foreign.equals(expectedForeign))throw failure("constraint_mismatch");
            var indexColumns=new LinkedHashMap<String,SortedMap<Integer,String>>();var indexUnique=new HashMap<String,Boolean>();
            try(var rows=metadata.getIndexInfo(context.catalog(),context.schema(),context.name(table),false,false)) {
                while(rows.next()) {
                    if(rows.getShort("TYPE")==DatabaseMetaData.tableIndexStatistic)continue;
                    String column=rows.getString("COLUMN_NAME"),name=rows.getString("INDEX_NAME");
                    if(column==null||name==null)throw failure("constraint_mismatch");
                    indexColumns.computeIfAbsent(name,k->new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"),column);indexUnique.put(name,rows.getBoolean("NON_UNIQUE"));
                }
            }
            var indexes=new ArrayList<String>();for(var entry:indexColumns.entrySet())indexes.add(indexUnique.get(entry.getKey())+":"+String.join(",",entry.getValue().values()));
            Collections.sort(indexes);var expectedIndexes=new ArrayList<>(List.of("false:"+context.name(primary)));if(foreignColumn!=null)expectedIndexes.add("true:"+context.name(foreignColumn));Collections.sort(expectedIndexes);
            if(!indexes.equals(expectedIndexes))throw failure("constraint_mismatch");
            return null;
        });
    }
}
