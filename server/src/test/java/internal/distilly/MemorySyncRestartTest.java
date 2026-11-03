package internal.distilly;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemorySyncRestartTest {
    private void ready(Process process,Path file)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!Files.exists(file)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
        assertTrue(Files.exists(file),"dedicated consumer fixture did not reach ready state; inspect target/memory-sync-consumer-process logs");
    }
    private Process launch(Path root,String step,Path log)throws Exception{
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
        // Explicit diagnostic profile only; default JVM execution and deadlines stay unchanged.
        if(Boolean.getBoolean("distilly.test.startup-tier1"))command.add("-XX:TieredStopAtLevel=1");
        command.addAll(List.of("-cp",cp,MemorySyncProcessFixture.class.getName(),root.toString(),step));
        var builder=new ProcessBuilder(command);
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
        long prepareStarted=0,recoveryStarted=0;boolean prepareCaptured=false,recoveryCaptured=false;
        try{
            preparing=launch(root,"prepare",output.resolve("prepare.log"));
            prepareStarted=System.nanoTime();long deadline=prepareStarted+TimeUnit.SECONDS.toNanos(30);
            while(!Files.exists(root.resolve("ready.json"))&&preparing.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
            // Freeze readiness before diagnostic I/O so late publication cannot benefit from capture time.
            boolean prepared=Files.exists(root.resolve("ready.json"));
            prepareCaptured=MemoryRecoveryObservation.capture(root,MemoryRecoveryObservation.Step.PREPARE,preparing,prepareStarted,
                    prepared?MemoryRecoveryObservation.Outcome.READY:preparing.isAlive()?MemoryRecoveryObservation.Outcome.DEADLINE_EXCEEDED:MemoryRecoveryObservation.Outcome.ABORTED,
                    output.resolve("prepare-before-stop.json"));
            assertTrue(prepared,"dedicated fixture did not reach committed ready state; inspect target/memory-sync-process/prepare.log");
            preparing.destroyForcibly();assertTrue(preparing.waitFor(10,TimeUnit.SECONDS));
            recovering=launch(root,"recover",output.resolve("recover.log"));
            recoveryStarted=System.nanoTime();boolean completed=recovering.waitFor(30,TimeUnit.SECONDS);
            recoveryCaptured=MemoryRecoveryObservation.capture(root,MemoryRecoveryObservation.Step.RECOVER,recovering,recoveryStarted,
                    completed?MemoryRecoveryObservation.Outcome.COMPLETED:MemoryRecoveryObservation.Outcome.DEADLINE_EXCEEDED,
                    output.resolve("recover-before-stop.json"));
            assertTrue(completed,"dedicated recovery fixture timeout");
            assertEquals(0,recovering.exitValue(),"dedicated recovery failed; inspect target/memory-sync-process/recover.log");
            assertTrue(Files.exists(root.resolve("recovered.json")));
            Files.copy(root.resolve("recovered.json"),output.resolve("result.json"),StandardCopyOption.REPLACE_EXISTING);
        }finally{
            try{
                try{MemoryRecoveryObservation.stop(root,MemoryRecoveryObservation.Step.PREPARE,preparing,prepareStarted,prepareCaptured,output.resolve("prepare-before-stop.json"),output.resolve("prepare-after-stop.json"));}
                finally{MemoryRecoveryObservation.stop(root,MemoryRecoveryObservation.Step.RECOVER,recovering,recoveryStarted,recoveryCaptured,output.resolve("recover-before-stop.json"),output.resolve("recover-after-stop.json"));}
            }finally{
                try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
            }
        }
    }
    @Test void consumerRecoversStageOnlyAndCommittedSideEffectWindows()throws Exception{
        Path root=Files.createTempDirectory("distilly-memory-consumer-process-").toAbsolutePath();
        Path output=Path.of("target/memory-sync-consumer-process").toAbsolutePath();Files.createDirectories(output);
        var processes=new ArrayList<Process>();
        try{
            var first=launch(root,"consumer_prepare",output.resolve("prepare.log"));processes.add(first);ready(first,root.resolve("consumer-ready-1.json"));
            first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));
            var second=launch(root,"consumer_recover_before",output.resolve("recover-before.log"));processes.add(second);ready(second,root.resolve("consumer-ready-2.json"));
            second.destroyForcibly();assertTrue(second.waitFor(10,TimeUnit.SECONDS));
            var third=launch(root,"consumer_recover_after",output.resolve("recover-after.log"));processes.add(third);
            assertTrue(third.waitFor(30,TimeUnit.SECONDS),"consumer recovery timeout");assertEquals(0,third.exitValue(),"inspect consumer recovery log");
            Files.copy(root.resolve("consumer-recovered.json"),output.resolve("result.json"),StandardCopyOption.REPLACE_EXISTING);
        }finally{
            for(Process p:processes)if(p.isAlive()){p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
