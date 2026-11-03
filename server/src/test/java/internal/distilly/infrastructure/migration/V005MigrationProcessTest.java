package internal.distilly.infrastructure.migration;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class V005MigrationProcessTest {
    @TempDir Path temporary;
    private Process launch(String step,Path output)throws Exception {
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,V005MigrationProcessFixture.class.getName(),temporary.toString(),step);
        var env=new HashMap<String,String>();for(String key:List.of("HOME","LANG","LC_ALL","TMPDIR","PATH","JAVA_HOME","JAVA_BIN")){String value=System.getenv(key);if(value!=null)env.put(key,value);}
        builder.environment().clear();builder.environment().putAll(env);return builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }
    @Test void committedPrefixSurvivesActualKillAndNewJvmResumesExactlyOnce()throws Exception {
        Path output=Path.of("target/v005-migration-process").toAbsolutePath();Files.createDirectories(output);
        var children=new ArrayList<Process>();
        try {
            var preparing=launch("prepare",output.resolve("prepare.log"));children.add(preparing);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(preparing.isAlive()&&!Files.exists(temporary.resolve("ready"))&&System.nanoTime()<end)Thread.sleep(50);
            assertTrue(Files.exists(temporary.resolve("ready")),"synthetic V005 committed boundary not reached; inspect target/v005-migration-process/prepare.log");
            preparing.destroyForcibly();assertTrue(preparing.waitFor(10,TimeUnit.SECONDS));
            var recovering=launch("recover",output.resolve("recover.log"));children.add(recovering);
            assertTrue(recovering.waitFor(30,TimeUnit.SECONDS),"synthetic V005 recovery process timeout");
            assertEquals(0,recovering.exitValue(),"inspect target/v005-migration-process/recover.log");
            assertTrue(Files.exists(temporary.resolve("recovered.json")));
            Files.copy(temporary.resolve("recovered.json"),output.resolve("result.json"),StandardCopyOption.REPLACE_EXISTING);
        } finally {
            for(var child:children)if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));}
        }
    }
}
