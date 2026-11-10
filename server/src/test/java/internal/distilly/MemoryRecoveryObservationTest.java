package internal.distilly;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static internal.distilly.MemoryRecoveryObservation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryRecoveryObservationTest {
    @TempDir Path directory;
    Path root;
    @BeforeEach void setup()throws IOException {root=Files.createTempDirectory(directory,"distilly-memory-sync-process-");}
    private final List<Recorder> recorders=new ArrayList<>();
    private Recorder trackedRecorder(java.util.function.LongSupplier clock,Sink sink){var r=new Recorder(clock,sink);recorders.add(r);return r;}
    private Recorder trackedStep(Path path,String step){var r=recorder(path,step);recorders.add(r);return r;}
    private void drained(Recorder recorder){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(recorder.pendingEvents()>0&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        assertEquals(0,recorder.pendingEvents(),"unit diagnostic writes did not drain after release");
    }
    @AfterEach void closeWriters(){
        recorders.forEach(Recorder::close);
        for(var r:recorders){
            drained(r);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(r.writerAlive()&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            assertFalse(r.writerAlive(),"unit phase writer still alive after release and close");
        }
    }
    Path trace(){return root.resolve("recover-progress.tsv");}
    Process process(AtomicBoolean alive)throws Exception {
        var process=mock(Process.class);
        when(process.isAlive()).thenAnswer(call->alive.get());when(process.exitValue()).thenReturn(137);
        when(process.destroyForcibly()).thenAnswer(call->{alive.set(false);return process;});
        when(process.waitFor(10,TimeUnit.SECONDS)).thenReturn(true);return process;
    }

    @Test void childClockIsRelativeMonotonicAndOnlyFixedStagesArePersisted()throws Exception {
        var clock=new AtomicLong(100);var text=new StringBuilder();var recorder=trackedRecorder(clock::get,text::append);
        recorder.mark(Stage.MAIN_ENTERED);clock.set(120);recorder.mark(Stage.SPRING_START_BEGIN);
        clock.set(150);recorder.mark(Stage.SPRING_READY);drained(recorder);Files.writeString(trace(),text);
        var result=read(root,Step.RECOVER);
        assertEquals("READ",result.status());assertEquals(List.of(0L,20L,50L),result.events().stream().map(Event::elapsedNanos).toList());
    }
    @Test void businessFailureStillClosesAndOriginalFailureSurvives()throws Exception {
        var text=new StringBuilder();var recorder=trackedRecorder(System::nanoTime,text::append);var closed=new AtomicBoolean();
        var failure=new IllegalStateException("synthetic-private-canary");
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var closing=recorder.closing(()->closed.set(true))){throw failure;}
        }));
        drained(recorder);assertTrue(closed.get());assertTrue(text.toString().contains("CONTEXT_CLOSE_BEGIN\t"));
        assertTrue(text.toString().contains("CONTEXT_CLOSE_END\t"));assertFalse(text.toString().contains(failure.getMessage()));
    }
    @Test void closeFailureIsRecordedAndPropagatedWithoutItsMessage(){
        var text=new StringBuilder();var recorder=trackedRecorder(System::nanoTime,text::append);
        assertThrows(IOException.class,()->recorder.closing(()->{throw new IOException("synthetic-private-canary");}).close());
        drained(recorder);assertTrue(text.toString().contains("CONTEXT_CLOSE_FAILED\t"));assertFalse(text.toString().contains("CONTEXT_CLOSE_END\t"));
        assertFalse(text.toString().contains("canary"));
    }
    @Test void traceIoFailureDoesNotSuppressResourceCloseOrBusinessFailure(){
        var recorder=trackedRecorder(System::nanoTime,line->{throw new IOException("synthetic-private-canary");});
        var closed=new AtomicBoolean();var original=new IllegalStateException("business assertion");
        assertSame(original,assertThrows(IllegalStateException.class,()->{
            try(var closing=recorder.closing(()->closed.set(true))){throw original;}
        }));
        drained(recorder);assertTrue(closed.get());assertEquals(2,recorder.writeFailures());
    }
    @Test void deadlineSnapshotPrecedesKillAndCleanupDoesNotRewriteIt()throws Exception {
        Files.writeString(trace(),"MAIN_ENTERED\t1\nCONTEXT_CLOSE_BEGIN\t7\n");
        var alive=new AtomicBoolean(true);var process=process(alive);Path before=directory.resolve("before.json"),after=directory.resolve("after.json");
        long start=System.nanoTime();
        assertTrue(capture(root,Step.RECOVER,process,start,Outcome.DEADLINE_EXCEEDED,before));
        String frozen=Files.readString(before);
        doAnswer(call->{
            assertEquals(frozen,Files.readString(before));Files.writeString(trace(),"FIXTURE_FAILED\t99\n");alive.set(false);return process;
        }).when(process).destroyForcibly();
        stop(root,Step.RECOVER,process,start,true,before,after);
        assertEquals(frozen,Files.readString(before));
        var snapshot=new ObjectMapper().readTree(frozen);
        assertEquals("DEADLINE_EXCEEDED",snapshot.get("status").asText());assertTrue(snapshot.get("process_alive").asBoolean());
        assertTrue(snapshot.get("exit_code").isNull());assertEquals("CONTEXT_CLOSE_BEGIN",snapshot.get("events").get(1).get("stage").asText());
        var cleanup=new ObjectMapper().readTree(Files.readString(after));assertEquals(137,cleanup.get("exit_code").asInt());
        assertEquals("CLEANED",cleanup.get("status").asText());verify(process).waitFor(10,TimeUnit.SECONDS);
    }
    @Test void exceptionBeforeNormalSnapshotStillCapturesBeforeCleanup()throws Exception {
        Files.writeString(trace(),"SPRING_START_BEGIN\t1\n");
        var alive=new AtomicBoolean(true);var process=process(alive);Path before=directory.resolve("before.json");
        doAnswer(call->{assertTrue(Files.exists(before));alive.set(false);return process;}).when(process).destroyForcibly();
        var failure=new IllegalStateException("business assertion");
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try{throw failure;}finally{stop(root,Step.RECOVER,process,System.nanoTime(),false,before,directory.resolve("after.json"));}
        }));
        assertEquals("ABORTED",new ObjectMapper().readTree(Files.readString(before)).get("status").asText());
        verify(process).destroyForcibly();assertFalse(alive.get());
    }
    @Test void outputIoFailureStillKillsOwnedProcess()throws Exception {
        var alive=new AtomicBoolean(true);var process=process(alive);Path missing=directory.resolve("missing");
        assertFalse(capture(root,Step.RECOVER,process,System.nanoTime(),Outcome.ABORTED,missing.resolve("before.json")));
        stop(root,Step.RECOVER,process,System.nanoTime(),false,missing.resolve("before.json"),missing.resolve("after.json"));
        assertFalse(alive.get());verify(process).destroyForcibly();
    }
    @Test void invalidAndNonMonotonicTraceCannotExposeText()throws Exception {
        for(String text:List.of("ENV_SYNTHETIC_CANARY\t1\n","SPRING_READY\t2\nMAIN_ENTERED\t1\n","MAIN_ENTERED\t-1\n")){
            Files.writeString(trace(),text);var parsed=read(root,Step.RECOVER);assertEquals("INVALID_TRACE",parsed.status());assertTrue(parsed.events().isEmpty());
        }
    }
    @Test void partialWriteRetainsOnlyCompletePriorStages()throws Exception {
        Files.writeString(trace(),"MAIN_ENTERED\t1\nSPRING_READY\t");var parsed=read(root,Step.RECOVER);
        assertEquals("PARTIAL_WRITE",parsed.status());assertEquals(List.of(new Event(Stage.MAIN_ENTERED,1)),parsed.events());
    }
    @Test void boundedReadAndMissingTraceAreExplicit()throws Exception {
        assertEquals("UNAVAILABLE",read(root,Step.RECOVER).status());Files.writeString(trace(),"x".repeat(MAX_BYTES+1));
        assertEquals("OVERSIZE",read(root,Step.RECOVER).status());
    }
    @Test void linksAndNonFixturePathsAreRefused()throws Exception {
        Path unrelated=directory.resolve("synthetic-unrelated.txt");Files.writeString(unrelated,"synthetic-private-canary");
        Files.createSymbolicLink(trace(),unrelated);assertEquals("REFUSED_PATH",read(root,Step.RECOVER).status());
        assertEquals("REFUSED_PATH",read(directory,Step.RECOVER).status());
        Path link=directory.resolve("distilly-memory-sync-process-link");Files.createSymbolicLink(link,root);
        assertEquals("REFUSED_PATH",read(link,Step.RECOVER).status());
    }
    @Test void consumerStepsHaveIndependentWhitelistedTracesAndCannotUseOtherRootKinds()throws Exception {
        Path consumer=Files.createTempDirectory(directory,"distilly-memory-consumer-process-");
        for(var step:List.of(Step.CONSUMER_PREPARE,Step.CONSUMER_RECOVER_BEFORE,Step.CONSUMER_RECOVER_AFTER)){
            var writing=trackedStep(consumer,step.name().toLowerCase(Locale.ROOT));writing.mark(Stage.SPRING_READY);drained(writing);
            assertEquals(List.of(Stage.MAIN_ENTERED,Stage.SPRING_READY),read(consumer,step).events().stream().map(Event::stage).toList());
            assertEquals("REFUSED_PATH",read(root,step).status());
        }
        assertEquals("REFUSED_PATH",read(consumer,Step.RECOVER).status());
        trackedStep(consumer,"untrusted-step").mark(Stage.FIXTURE_FAILED);
        assertFalse(Files.exists(consumer.resolve("untrusted-step-progress.tsv")));
    }
    @Test void consumerDeadlineRemainsFailedEvenWhenChildPublishesSuccessDuringCleanup()throws Exception {
        Path consumer=Files.createTempDirectory(directory,"distilly-memory-consumer-process-");
        var recorder=trackedStep(consumer,"consumer_recover_after");recorder.mark(Stage.SPRING_START_BEGIN);drained(recorder);
        var alive=new AtomicBoolean(true);var child=process(alive);Path before=directory.resolve("consumer-before.json"),after=directory.resolve("consumer-after.json");
        assertTrue(capture(consumer,Step.CONSUMER_RECOVER_AFTER,child,System.nanoTime(),Outcome.DEADLINE_EXCEEDED,before));
        String frozen=Files.readString(before);
        doAnswer(call->{recorder.mark(Stage.FIXTURE_COMPLETE);alive.set(false);return child;}).when(child).destroyForcibly();
        stop(consumer,Step.CONSUMER_RECOVER_AFTER,child,System.nanoTime(),true,before,after);
        assertEquals(frozen,Files.readString(before));
        assertEquals("DEADLINE_EXCEEDED",new ObjectMapper().readTree(frozen).get("status").asText());
    }
    @Test void securityFailureInDiagnosticSinkCannotReplaceBusinessFailure()throws Exception {
        var recorder=trackedRecorder(System::nanoTime,line->{throw new SecurityException("SYNTHETIC_PRIVATE_CANARY");});
        var failure=new IllegalStateException("original");var closed=new AtomicBoolean();
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var closing=recorder.closing(()->closed.set(true))){throw failure;}
        }));drained(recorder);assertTrue(closed.get());assertEquals(2,recorder.writeFailures());
    }
}
