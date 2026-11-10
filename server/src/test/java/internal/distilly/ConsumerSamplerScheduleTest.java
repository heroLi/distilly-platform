package internal.distilly;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static internal.distilly.ConsumerRecoveryDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

/** Virtual child time and controlled collectors only; never starts a Process or Spring. */
class ConsumerSamplerScheduleTest {
    @TempDir Path directory;
    Path root;
    @BeforeEach void setup()throws Exception{root=Files.createTempDirectory(directory,"distilly-memory-consumer-process-");}
    static long seconds(long value){return TimeUnit.SECONDS.toNanos(value);}
    Snapshot empty(int sample){return new Snapshot(1,Status.CAPTURED,sample,1,0,0,1,List.of(),List.of());}
    static void hold(CountDownLatch release){
        boolean interrupted=false;
        for(;;)try{release.await();break;}catch(InterruptedException stopped){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    static final class VirtualTime implements SampleWait {
        final AtomicLong now=new AtomicLong();
        final List<Long> targets=new ArrayList<>();
        final Set<Thread> threads=new HashSet<>();
        boolean stopped;
        @Override public synchronized void until(long started,long delay)throws InterruptedException{
            threads.add(Thread.currentThread());targets.add(started+delay);notifyAll();
            while(now.get()-started<delay){
                if(stopped||Thread.interrupted())throw new InterruptedException();
                wait();
            }
            if(stopped||Thread.interrupted())throw new InterruptedException();
        }
        synchronized void advance(long seconds){now.set(ConsumerSamplerScheduleTest.seconds(seconds));notifyAll();}
        synchronized List<Long> registrations(int count)throws InterruptedException{
            long began=System.nanoTime();
            while(targets.size()<count){
                long remaining=TimeUnit.SECONDS.toNanos(2)-(System.nanoTime()-began);
                assertTrue(remaining>0,"controlled sampler did not register its wait");
                TimeUnit.NANOSECONDS.timedWait(this,remaining);
            }
            return targets.stream().sorted().toList();
        }
        synchronized void stop(){stopped=true;notifyAll();}
        void joinAll()throws InterruptedException{
            List<Thread> copy;synchronized(this){copy=List.copyOf(threads);}
            for(Thread thread:copy){thread.join(2000);assertFalse(thread.isAlive(),"controlled sampler did not clean up");}
        }
    }
    void finish(Sampler sampler,VirtualTime time,CountDownLatch release)throws Exception{
        sampler.close();release.countDown();time.stop();time.joinAll();
    }
    @Test void blockedFirstCollectorCannotPushSecondDispatchPastTwentySeconds()throws Exception{
        var time=new VirtualTime();var first=new CountDownLatch(1);var second=new CountDownLatch(1);var release=new CountDownLatch(1);
        var daemon=new AtomicBoolean(true);
        var sampler=start(root,seconds(10),time.now::get,time,index->{
            daemon.set(daemon.get()&&Thread.currentThread().isDaemon());
            if(index==1){first.countDown();hold(release);}else second.countDown();return empty(index);
        });
        try{
            time.registrations(1);time.advance(10);assertTrue(first.await(2,TimeUnit.SECONDS));
            time.advance(20);
            assertTrue(second.await(2,TimeUnit.SECONDS),"second dispatch serialized behind blocked first collection");
            assertEquals(1,release.getCount());assertTrue(daemon.get());
        }finally{finish(sampler,time,release);}
    }
    @Test void firstCompletionAtTwentySixDoesNotShiftSecondTargetToThirtySix()throws Exception{
        var time=new VirtualTime();var first=new CountDownLatch(1);var release=new CountDownLatch(1);
        var sampler=start(root,seconds(10),time.now::get,time,index->{if(index==1){first.countDown();hold(release);}return empty(index);});
        try{
            time.registrations(1);time.advance(10);assertTrue(first.await(2,TimeUnit.SECONDS));
            time.advance(26);release.countDown();
            assertEquals(List.of(seconds(10),seconds(20)),time.registrations(2));
        }finally{finish(sampler,time,release);}
    }
    @Test void normalCollectorsKeepTwoSharedOriginTargetsAndExactlyTwoDispatches()throws Exception{
        var time=new VirtualTime();var first=new CountDownLatch(1);var second=new CountDownLatch(1);var release=new CountDownLatch(1);
        var observed=new ConcurrentHashMap<Integer,Long>();
        var sampler=start(root,seconds(10),time.now::get,time,index->{observed.put(index,time.now.get());if(index==1)first.countDown();else second.countDown();return empty(index);});
        try{
            time.registrations(1);time.advance(10);assertTrue(first.await(2,TimeUnit.SECONDS));
            assertEquals(List.of(seconds(10),seconds(20)),time.registrations(2));
            time.advance(20);assertTrue(second.await(2,TimeUnit.SECONDS));
            time.advance(100);time.joinAll();assertEquals(Map.of(1,seconds(10),2,seconds(20)),observed);
        }finally{finish(sampler,time,release);}
    }
    @Test void closeBeforeTargetsCancelsWithoutDispatch()throws Exception{
        var time=new VirtualTime();var called=new AtomicInteger();var release=new CountDownLatch(1);
        var sampler=start(root,seconds(10),time.now::get,time,index->{called.incrementAndGet();return empty(index);});
        try{time.registrations(1);sampler.close();time.advance(100);time.joinAll();assertEquals(0,called.get());}
        finally{finish(sampler,time,release);}
    }
    @Test void closeDoesNotJoinAnUninterruptibleCollectorAndCancelsOtherDispatch()throws Exception{
        var time=new VirtualTime();var first=new CountDownLatch(1);var release=new CountDownLatch(1);var called=new AtomicInteger();
        var sampler=start(root,seconds(10),time.now::get,time,index->{called.incrementAndGet();if(index==1){first.countDown();hold(release);}return empty(index);});
        try{
            time.registrations(1);time.advance(10);assertTrue(first.await(2,TimeUnit.SECONDS));
            sampler.close();assertEquals(1,release.getCount());time.advance(20);release.countDown();
            time.joinAll();assertEquals(1,called.get());
        }finally{finish(sampler,time,release);}
    }
    @Test void failedFirstCollectorStillAllowsSecondAndPersistsOnlyFixedUnavailable()throws Exception{
        var time=new VirtualTime();var first=new CountDownLatch(1);var second=new CountDownLatch(1);var release=new CountDownLatch(1);
        var sampler=start(root,seconds(10),time.now::get,time,index->{
            if(index==1){first.countDown();throw new IllegalStateException("SYNTHETIC_PRIVATE_CANARY");}
            second.countDown();return empty(index);
        });
        try{
            time.registrations(1);time.advance(10);assertTrue(first.await(2,TimeUnit.SECONDS));time.registrations(2);
            time.advance(20);assertTrue(second.await(2,TimeUnit.SECONDS));time.joinAll();
            String text=Files.readString(root.resolve("consumer-thread-1.json"));assertFalse(text.contains("CANARY"));
            assertEquals(Status.UNAVAILABLE,json().readValue(text,Snapshot.class).status());
            assertEquals(Status.CAPTURED,json().readValue(root.resolve("consumer-thread-2.json").toFile(),Snapshot.class).status());
        }finally{finish(sampler,time,release);}
    }
}
