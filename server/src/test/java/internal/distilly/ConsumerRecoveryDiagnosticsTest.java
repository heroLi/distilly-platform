package internal.distilly;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static internal.distilly.ConsumerRecoveryDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerRecoveryDiagnosticsTest {
    @TempDir Path directory;
    Path root;
    @BeforeEach void setup()throws Exception{root=Files.createTempDirectory(directory,"distilly-memory-consumer-process-");}
    Snapshot empty(int index){return new Snapshot(1,Status.CAPTURED,index,1,0,0,1,List.of(),List.of());}

    @Test void ownJvmStackAndHeldMonitorAreBoundedWithoutThreadNamesOrMessageValues()throws Exception {
        Object monitor=new Object();var ready=new CountDownLatch(1);var done=new CountDownLatch(1);
        var thread=new Thread(()->{synchronized(monitor){ready.countDown();try{done.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}},"SYNTHETIC_PRIVATE_CANARY");
        thread.setDaemon(true);thread.start();
        try{
            assertTrue(ready.await(2,TimeUnit.SECONDS));
            var value=collect(1,System.nanoTime(),thread.getId());
            var main=value.threads().stream().filter(r->r.role()==Role.MAIN).findFirst().orElseThrow();
            assertEquals(thread.getId(),main.id());assertFalse(main.frames().isEmpty());
            assertTrue(main.monitors().stream().anyMatch(l->l.identity()==System.identityHashCode(monitor)));
            assertTrue(value.threads().size()<=MAX_THREADS);assertTrue(main.frames().size()<=MAX_FRAMES);
            String encoded=json().writeValueAsString(value);assertFalse(encoded.contains("SYNTHETIC_PRIVATE_CANARY"));
            assertFalse(encoded.contains("threadName"));assertFalse(encoded.contains("fileName"));assertFalse(encoded.contains("environment"));
        }finally{done.countDown();thread.join(2000);assertFalse(thread.isAlive());}
    }
    @Test void collectorFailurePersistsOnlyFixedUnavailableStatus()throws Exception {
        assertTrue(capture(root,1,index->{throw new IllegalStateException("SYNTHETIC_PRIVATE_CANARY");}));
        String text=Files.readString(root.resolve("consumer-thread-1.json"));assertFalse(text.contains("CANARY"));
        assertEquals(Status.UNAVAILABLE,json().readValue(text,Snapshot.class).status());
    }
    @Test void outputFailureAndSamplerCloseDoNotReplaceOriginalBusinessException()throws Exception {
        Path missing=root.resolve("missing");assertFalse(capture(missing,1,this::empty));
        var called=new AtomicInteger();var original=new IllegalStateException("original");
        assertSame(original,assertThrows(IllegalStateException.class,()->{
            try(var sampler=start(root,60_000,index->{called.incrementAndGet();return empty(index);})){throw original;}
        }));assertEquals(0,called.get());
    }
    @Test void exactlyTwoDaemonSamplesAreScheduledAndCloseDoesNotJoin()throws Exception {
        var two=new CountDownLatch(2);var count=new AtomicInteger();var daemon=new AtomicBoolean(true);
        try(var sampler=start(root,1,index->{daemon.set(daemon.get()&&Thread.currentThread().isDaemon());count.incrementAndGet();two.countDown();return empty(index);})){assertTrue(two.await(2,TimeUnit.SECONDS));}
        assertTrue(daemon.get());assertEquals(2,count.get());
    }
    @Test void parentReemitsOnlyStrictSnapshotSchemaAndRejectsUnknownPrivateFields()throws Exception {
        Path output=Files.createDirectory(directory.resolve("output"));
        assertTrue(capture(root,1,this::empty));
        Files.writeString(root.resolve("consumer-thread-2.json"),"{\"threadName\":\"SYNTHETIC_PRIVATE_CANARY\"}");
        copyBeforeStop(root,output);
        assertEquals(Status.CAPTURED,json().readValue(output.resolve("thread-sample-1.json").toFile(),Snapshot.class).status());
        assertEquals(Status.UNAVAILABLE,json().readValue(output.resolve("thread-sample-2.json").toFile(),Snapshot.class).status());
        assertFalse(Files.readString(output.resolve("thread-sample-2-status.json")).contains("CANARY"));
    }
    @Test void unsafeRootsLinksOversizeAndSampleIdentityAreRejected()throws Exception {
        assertFalse(capture(directory,1,this::empty));
        Path outside=directory.resolve("private.json");Files.writeString(outside,"SYNTHETIC_PRIVATE_CANARY");
        Files.createSymbolicLink(root.resolve("consumer-thread-1.json"),outside);
        Files.writeString(root.resolve("consumer-thread-2.json"),"x".repeat(MAX_BYTES+1));
        Path output=Files.createDirectory(directory.resolve("output"));copyBeforeStop(root,output);
        assertEquals(Status.UNAVAILABLE,json().readValue(output.resolve("thread-sample-1.json").toFile(),Snapshot.class).status());
        assertEquals(Status.UNAVAILABLE,json().readValue(output.resolve("thread-sample-2.json").toFile(),Snapshot.class).status());
        Files.delete(root.resolve("consumer-thread-1.json"));Files.writeString(root.resolve("consumer-thread-1.json"),json().writeValueAsString(empty(2)));
        copyBeforeStop(root,output);assertEquals(Status.UNAVAILABLE,json().readValue(output.resolve("thread-sample-1.json").toFile(),Snapshot.class).status());
    }
    @Test void frameFieldsCannotBecomeArbitraryPathsOrArgumentStrings() {
        var frame=new Frame("/private/SYNTHETIC_PRIVATE_CANARY","arg=value and message",1);
        assertEquals("OTHER",frame.type());assertEquals("OTHER",frame.method());
        assertThrows(IllegalArgumentException.class,()->new Snapshot(1,Status.CAPTURED,3,0,0,0,0,List.of(),List.of()));
        assertFalse(capture(root,3,this::empty));
        assertDoesNotThrow(()->new Sampler(new Thread(){@Override public void interrupt(){throw new SecurityException("SYNTHETIC_PRIVATE_CANARY");}}).close());
    }
    @Test void missingNewSampleCannotLeaveEarlierCapturedOutputLookingCurrent()throws Exception {
        Path output=Files.createDirectory(directory.resolve("output"));assertTrue(capture(root,1,this::empty));
        copyBeforeStop(root,output);Files.delete(root.resolve("consumer-thread-1.json"));copyBeforeStop(root,output);
        assertEquals(Status.UNAVAILABLE,json().readValue(output.resolve("thread-sample-1.json").toFile(),Snapshot.class).status());
    }
}
