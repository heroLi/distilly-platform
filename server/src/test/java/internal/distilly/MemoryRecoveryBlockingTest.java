package internal.distilly;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static internal.distilly.MemoryRecoveryObservation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled sink only. No Spring, recovery subprocess, database, real disk stall or model. */
class MemoryRecoveryBlockingTest {
    private void hold(CountDownLatch entered,CountDownLatch release)throws IOException {
        entered.countDown();
        try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("synthetic_interrupted");}
    }
    private void closeIfSupported(Recorder recorder)throws Exception {
        if((Object)recorder instanceof AutoCloseable resource)resource.close();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(recorder.writerAlive()&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        assertFalse(recorder.writerAlive(),"test writer did not stop after controlled release");
        assertEquals(0,recorder.pendingEvents());
    }
    @Test void blockedDiagnosticWriteMustNotDelayRecoveryCallback()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var recovered=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();var recorder=new Recorder(System::nanoTime,line->hold(entered,release));
        Future<?> task=null;
        try {
            task=pool.submit(()->{recorder.mark(Stage.RECOVERY_ASSERTIONS_BEGIN);recovered.countDown();});
            assertTrue(entered.await(2,TimeUnit.SECONDS),"diagnostic sink not reached");
            assertTrue(recovered.await(1,TimeUnit.SECONDS),"diagnostic sink blocks recovery callback before release");
        }finally{
            release.countDown();if(task!=null)task.get(2,TimeUnit.SECONDS);closeIfSupported(recorder);
            pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));
        }
    }
    @Test void blockedCloseMarkerMustNotDelayResourceCloseOrOriginalBusinessFailure()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new CountDownLatch(1);
        var original=new IllegalStateException("synthetic_business_failure");var pool=Executors.newSingleThreadExecutor();
        var recorder=new Recorder(System::nanoTime,line->hold(entered,release));Future<?> task=null;
        try {
            task=pool.submit(()->{
                try(var resource=recorder.closing(closed::countDown)){throw original;}
                catch(Exception failure){throw new CompletionException(failure);}
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS),"diagnostic close marker not reached");
            assertTrue(closed.await(1,TimeUnit.SECONDS),"diagnostic sink blocks actual resource cleanup before release");
            var finished=task;var failure=assertThrows(ExecutionException.class,()->finished.get(1,TimeUnit.SECONDS));
            assertSame(original,failure.getCause().getCause());
        }finally{
            release.countDown();if(task!=null)try{task.get(2,TimeUnit.SECONDS);}catch(ExecutionException expected){}
            closeIfSupported(recorder);pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));
        }
    }
    @Test void releasedWriterPreservesCallTimeAndOrderWithoutHoldingBusinessCallback()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var clock=new AtomicLong(100);
        var lines=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var recorder=new Recorder(clock::get,line->{hold(entered,release);lines.add(line);});
        try{
            recorder.mark(Stage.MAIN_ENTERED);assertTrue(entered.await(2,TimeUnit.SECONDS));
            clock.set(120);recorder.mark(Stage.RECOVERY_ASSERTIONS_BEGIN);
            clock.set(150);recorder.mark(Stage.RECOVERY_ASSERTIONS_END);
            assertTrue(lines.isEmpty(),"sink remains controlled until release");
        }finally{release.countDown();closeIfSupported(recorder);}
        assertEquals(java.util.List.of("MAIN_ENTERED\t0\n","RECOVERY_ASSERTIONS_BEGIN\t20\n","RECOVERY_ASSERTIONS_END\t50\n"),lines);
    }
    @Test void blockedSinkCannotGrowQueueOrMakePublisherWaitForCapacity()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        var lines=new AtomicInteger();var recorder=new Recorder(System::nanoTime,line->{hold(entered,release);lines.incrementAndGet();});
        try{
            recorder.mark(Stage.MAIN_ENTERED);assertTrue(entered.await(2,TimeUnit.SECONDS));
            pool.submit(()->{for(int i=0;i<1000;i++)recorder.mark(Stage.SPRING_START_BEGIN);}).get(1,TimeUnit.SECONDS);
            assertEquals(Recorder.MAX_EVENTS,recorder.pendingEvents());assertEquals(937,recorder.droppedEvents());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));closeIfSupported(recorder);}
        assertEquals(Recorder.MAX_EVENTS,lines.get());
    }
    @Test void blockedThenFailingSinkCannotReplaceBusinessOrActualCloseFailure()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new CountDownLatch(1);
        var original=new IllegalStateException("synthetic_primary");var closeFailure=new IOException("synthetic_close");
        var pool=Executors.newSingleThreadExecutor();
        var recorder=new Recorder(System::nanoTime,line->{hold(entered,release);throw new IOException("SYNTHETIC_PRIVATE_CANARY");});
        Future<?> task=null;
        try{
            task=pool.submit(()->{
                try(var resource=recorder.closing(()->{closed.countDown();throw closeFailure;})){throw original;}
                catch(Exception e){throw new CompletionException(e);}
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(closed.await(1,TimeUnit.SECONDS));
            var finished=task;var failure=assertThrows(ExecutionException.class,()->finished.get(1,TimeUnit.SECONDS));
            assertSame(original,failure.getCause().getCause());assertArrayEquals(new Throwable[]{closeFailure},original.getSuppressed());
        }finally{
            release.countDown();if(task!=null)try{task.get(2,TimeUnit.SECONDS);}catch(ExecutionException expected){}
            closeIfSupported(recorder);pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));
        }
        assertEquals(2,recorder.writeFailures());
    }
    @Test void recorderCloseDoesNotWaitForBlockedWriterAndRefusesLaterEvents()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        var written=new AtomicInteger();var recorder=new Recorder(System::nanoTime,line->{hold(entered,release);written.incrementAndGet();});
        try{
            recorder.mark(Stage.MAIN_ENTERED);assertTrue(entered.await(2,TimeUnit.SECONDS));
            pool.submit(recorder::close).get(1,TimeUnit.SECONDS);
            assertTrue(recorder.writerAlive(),"blocked sink remains owned by a daemon until release");
            recorder.mark(Stage.FIXTURE_COMPLETE);assertEquals(1,recorder.droppedEvents());
        }finally{release.countDown();closeIfSupported(recorder);pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
        assertEquals(1,written.get());
    }
}
