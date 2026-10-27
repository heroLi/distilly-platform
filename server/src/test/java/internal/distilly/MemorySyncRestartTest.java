package internal.distilly;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemorySyncRestartTest {
    private Process launch(Path root,String step,Path log)throws Exception{
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                MemorySyncProcessFixture.class.getName(),root.toString(),step);
        var clean=new HashMap<String,String>();
        for(String key:List.of("HOME","LANG","LC_ALL","TMPDIR","PATH","JAVA_HOME","JAVA_BIN")){
            String value=System.getenv(key);if(value!=null)clean.put(key,value);
        }
        builder.environment().clear();builder.environment().putAll(clean);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());return builder.start();
    }
    @Test void forceKillAndNewJvmPreserveUnknownAndDeleteOrdering()throws Exception{
        Path root=Files.createTempDirectory("distilly-memory-sync-process-").toAbsolutePath();
        Path output=Path.of("target/memory-sync-process").toAbsolutePath();Files.createDirectories(output);
        Process preparing=null,recovering=null;
        try{
            preparing=launch(root,"prepare",output.resolve("prepare.log"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!Files.exists(root.resolve("ready.json"))&&preparing.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
            assertTrue(Files.exists(root.resolve("ready.json")),"dedicated fixture did not reach committed ready state; inspect target/memory-sync-process/prepare.log");
            preparing.destroyForcibly();assertTrue(preparing.waitFor(10,TimeUnit.SECONDS));
            recovering=launch(root,"recover",output.resolve("recover.log"));
            assertTrue(recovering.waitFor(30,TimeUnit.SECONDS),"dedicated recovery fixture timeout");
            assertEquals(0,recovering.exitValue(),"dedicated recovery failed; inspect target/memory-sync-process/recover.log");
            assertTrue(Files.exists(root.resolve("recovered.json")));
            Files.copy(root.resolve("recovered.json"),output.resolve("result.json"),StandardCopyOption.REPLACE_EXISTING);
        }finally{
            for(Process process:Arrays.asList(preparing,recovering))if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
