package internal.distilly;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exact ready helper, virtual time and barriers. Never launches the recovery fixture or a Process. */
class MemoryReadinessDeadlineTest {
    @TempDir Path directory;
    long seconds(long value){return TimeUnit.SECONDS.toNanos(value);}
    boolean appearsAfterPause(long observedSeconds)throws Exception {
        Path file=directory.resolve("ready");var clock=new AtomicLong();var paused=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();Future<Boolean> running=null;
        try{
            running=pool.submit(()->MemorySyncRestartTest.ready(clock::get,()->Files.exists(file),()->true,
                    ()->{paused.countDown();release.await();}));
            assertTrue(paused.await(2,TimeUnit.SECONDS),"ready helper did not reach controlled pause");
            clock.set(seconds(observedSeconds));Files.createFile(file);release.countDown();
            return running.get(2,TimeUnit.SECONDS);
        }finally{
            release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));
        }
    }
    @Test void afterDeadlineFileCannotBeReportedReady()throws Exception{assertFalse(appearsAfterPause(31));}
    @Test void exactDeadlineFileCannotBeBackdated()throws Exception{assertFalse(appearsAfterPause(30));}
    @Test void beforeDeadlineFileStillBecomesReady()throws Exception{assertTrue(appearsAfterPause(29));}
    @Test void probeThatFinishesAfterDeadlineCannotUseItsEarlierStartTime()throws Exception {
        Path file=Files.createFile(directory.resolve("ready"));var clock=new AtomicLong();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{
            var running=pool.submit(()->MemorySyncRestartTest.ready(clock::get,()->{
                entered.countDown();
                try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("synthetic_interrupted",e);}
                return Files.exists(file);
            },()->true,()->fail("existing marker must not sleep")));
            assertTrue(entered.await(2,TimeUnit.SECONDS));clock.set(seconds(31));release.countDown();
            assertFalse(running.get(2,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void immediateCommittedFileRetainsExistingExitedChildSemantics()throws Exception {
        Path file=Files.createFile(directory.resolve("ready"));var aliveReads=new AtomicInteger();
        assertTrue(MemorySyncRestartTest.ready(()->-seconds(100),()->Files.exists(file),
                ()->{aliveReads.incrementAndGet();return false;},()->fail("immediate marker must not sleep")));
        assertEquals(0,aliveReads.get());
    }
    @Test void childExitWithoutFileStopsWithoutWaitingOrOwningCleanup()throws Exception {
        assertFalse(MemorySyncRestartTest.ready(()->0,()->false,()->false,()->fail("exited child must not sleep")));
    }
    @Test void probeFailurePropagatesToCallerAndCallerFinallyRuns() {
        var original=new SecurityException("SYNTHETIC_PRIVATE_CANARY");var cleaned=new AtomicInteger();
        var actual=assertThrows(SecurityException.class,()->{
            try{MemorySyncRestartTest.ready(()->0,()->{throw original;},()->true,()->fail("failing probe must not sleep"));}
            finally{cleaned.incrementAndGet();}
        });assertSame(original,actual);assertEquals(1,cleaned.get());
    }
    @Test void interruptedPausePropagatesAndControlledThreadAlwaysCleansUp()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();var cleaned=new AtomicInteger();
        var thread=new Thread(()->{
            try{MemorySyncRestartTest.ready(()->0,()->false,()->true,()->{entered.countDown();release.await();});}
            catch(Throwable e){failure.set(e);}finally{cleaned.incrementAndGet();done.countDown();}
        },"synthetic-ready-deadline-unit");thread.setDaemon(true);thread.start();
        try{
            assertTrue(entered.await(2,TimeUnit.SECONDS));thread.interrupt();assertTrue(done.await(2,TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class,failure.get());assertEquals(1,cleaned.get());
        }finally{release.countDown();thread.interrupt();thread.join(2000);assertFalse(thread.isAlive());}
    }
}
