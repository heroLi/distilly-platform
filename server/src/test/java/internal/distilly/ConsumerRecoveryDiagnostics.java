package internal.distilly;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Own test JVM only. No names, arguments, environment, exception messages or raw dump files. */
final class ConsumerRecoveryDiagnostics {
    static final int MAX_BYTES=256*1024, MAX_THREADS=32, MAX_FRAMES=12, MAX_LOCKS=8;
    private static final class Codec { static final ObjectMapper JSON=new ObjectMapper(); }
    static ObjectMapper json(){return Codec.JSON;} // No JSON initialization on the fixture main/start path.
    enum Status { CAPTURED, UNAVAILABLE }
    enum Role { MAIN, OTHER }
    record Frame(String type,String method,int line) {
        Frame { type=safeType(type);method=method!=null&&method.matches("[A-Za-z0-9_$<>]{1,96}")?method:"OTHER"; }
    }
    record Lock(String type,int identity) { Lock { type=safeType(type); } }
    record Row(long id,Role role,Thread.State state,long blocked,long waited,long owner,Lock lock,
               List<Lock> monitors,List<Lock> synchronizers,List<Frame> frames,long cpuNanos) {
        Row {
            if(id<=0||role==null||state==null||monitors.size()>MAX_LOCKS||synchronizers.size()>MAX_LOCKS||frames.size()>MAX_FRAMES)
                throw new IllegalArgumentException("invalid_diagnostic_row");
            monitors=List.copyOf(monitors);synchronizers=List.copyOf(synchronizers);frames=List.copyOf(frames);
        }
    }
    record Snapshot(int schemaVersion,Status status,int sample,long elapsedNanos,long processCpuNanos,
                    long descendants,long collectionNanos,List<Row> threads,List<Long> deadlocked) {
        Snapshot {
            if(schemaVersion!=1||status==null||sample<1||sample>2||elapsedNanos<0||collectionNanos<0
                    ||threads.size()>MAX_THREADS||deadlocked.size()>MAX_THREADS)
                throw new IllegalArgumentException("invalid_diagnostic_snapshot");
            threads=List.copyOf(threads);deadlocked=List.copyOf(deadlocked);
        }
    }
    @FunctionalInterface interface Source { Snapshot collect(int sample)throws Exception; }
    @FunctionalInterface interface SampleWait { void until(long started,long delayNanos)throws InterruptedException; }
    private static String safeType(String value) {
        return value!=null&&value.length()<=180&&value.matches("(?:java|javax|jdk|sun|com\\.sun|org\\.springframework|org\\.h2|com\\.zaxxer|internal\\.distilly)\\.[A-Za-z0-9_.$]+")?value:"OTHER";
    }
    private static Lock lock(LockInfo value){return value==null?null:new Lock(value.getClassName(),value.getIdentityHashCode());}
    static Snapshot collect(int sample,long started,long mainId) {
        long begin=System.nanoTime();var bean=ManagementFactory.getThreadMXBean();
        var ids=new LinkedHashSet<Long>();ids.add(mainId);
        Arrays.stream(bean.getAllThreadIds()).sorted().limit(MAX_THREADS).forEach(ids::add);
        long[] selected=ids.stream().limit(MAX_THREADS).mapToLong(Long::longValue).toArray();
        var infos=bean.getThreadInfo(selected,bean.isObjectMonitorUsageSupported(),bean.isSynchronizerUsageSupported(),MAX_FRAMES);
        var rows=new ArrayList<Row>();
        boolean cpu=bean.isThreadCpuTimeSupported()&&bean.isThreadCpuTimeEnabled(); // Never enable monitoring.
        for(var info:infos)if(info!=null){
            var frames=Arrays.stream(info.getStackTrace()).limit(MAX_FRAMES).map(f->new Frame(f.getClassName(),f.getMethodName(),f.getLineNumber())).toList();
            var monitors=Arrays.stream(info.getLockedMonitors()).limit(MAX_LOCKS).map(ConsumerRecoveryDiagnostics::lock).toList();
            var synchronizers=Arrays.stream(info.getLockedSynchronizers()).limit(MAX_LOCKS).map(ConsumerRecoveryDiagnostics::lock).toList();
            rows.add(new Row(info.getThreadId(),info.getThreadId()==mainId?Role.MAIN:Role.OTHER,info.getThreadState(),
                    info.getBlockedCount(),info.getWaitedCount(),info.getLockOwnerId(),lock(info.getLockInfo()),monitors,synchronizers,frames,
                    cpu?bean.getThreadCpuTime(info.getThreadId()):-1));
        }
        long[] deadlocked=bean.isSynchronizerUsageSupported()?bean.findDeadlockedThreads():bean.findMonitorDeadlockedThreads();
        List<Long> dead=deadlocked==null?List.of():Arrays.stream(deadlocked).limit(MAX_THREADS).boxed().toList();
        long processCpu=ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L);
        long descendants;try(var children=ProcessHandle.current().descendants()){descendants=children.limit(MAX_THREADS).count();}
        return new Snapshot(1,Status.CAPTURED,sample,System.nanoTime()-started,processCpu,descendants,System.nanoTime()-begin,rows,dead);
    }
    static boolean capture(Path root,int sample,Source source) {
        if(sample<1||sample>2)return false;
        if(!MemoryRecoveryObservation.safeRoot(root,MemoryRecoveryObservation.Step.CONSUMER_RECOVER_AFTER))return false;
        Snapshot snapshot;
        try { snapshot=Objects.requireNonNull(source.collect(sample)); }
        catch(Exception failure){snapshot=new Snapshot(1,Status.UNAVAILABLE,sample,0,-1,-1,0,List.of(),List.of());}
        try {
            String value=json().writeValueAsString(snapshot);if(value.length()>MAX_BYTES)return false;
            return MemoryRecoveryObservation.write(root.resolve("consumer-thread-"+sample+".json"),value);
        }catch(Exception failure){return false;}
    }
    static final class Sampler implements AutoCloseable {
        private final AtomicBoolean closed;
        private final Thread[] threads;
        Sampler(Thread... threads){this(new AtomicBoolean(),threads);}
        private Sampler(AtomicBoolean closed,Thread[] threads){this.closed=closed;this.threads=threads.clone();}
        @Override public void close(){
            closed.set(true);
            for(Thread thread:threads)try{if(thread!=null)thread.interrupt();}catch(SecurityException denied){/* daemon cannot hold up exit */}
        }
    }
    static Sampler start(Path root,String step) {
        if(!"consumer_recover_after".equals(step))return new Sampler();
        long started=System.nanoTime(),main=Thread.currentThread().getId();
        return start(root,10_000,sample->collect(sample,started,main));
    }
    static Sampler start(Path root,long intervalMillis,Source source) {
        return start(root,TimeUnit.MILLISECONDS.toNanos(intervalMillis),System::nanoTime,(started,delay)->{
            long remaining;
            while((remaining=delay-(System.nanoTime()-started))>0)TimeUnit.NANOSECONDS.sleep(remaining);
        },source);
    }
    static Sampler start(Path root,long intervalNanos,LongSupplier clock,SampleWait wait,Source source) {
        if(!MemoryRecoveryObservation.safeRoot(root,MemoryRecoveryObservation.Step.CONSUMER_RECOVER_AFTER))return new Sampler();
        try {
            long started=clock.getAsLong();var closed=new AtomicBoolean();var threads=new Thread[2];
            // Two independent, bounded tasks: collection/write latency in sample 1 must not reschedule sample 2.
            for(int index=0;index<threads.length;index++){
                int sample=index+1;long delay=sample*intervalNanos;
                threads[index]=new Thread(()->{
                    try {wait.until(started,delay);}catch(InterruptedException stopped){return;}
                    if(!closed.get()&&!Thread.currentThread().isInterrupted())capture(root,sample,source);
                },"consumer-test-observation-"+sample);
                threads[index].setDaemon(true);
            }
            var sampler=new Sampler(closed,threads);
            try{for(Thread thread:threads)thread.start();}
            catch(RuntimeException unavailable){sampler.close();return new Sampler();}
            return sampler;
        }catch(RuntimeException unavailable){return new Sampler();}
    }
    /** Read only two bounded own-fixture files, deserialize a strict record schema and re-emit sanitized fields. */
    static void copyBeforeStop(Path root,Path output) {
        for(int sample=1;sample<=2;sample++){
            Path source=root.resolve("consumer-thread-"+sample+".json");String status="UNAVAILABLE";
            try {
                if(!MemoryRecoveryObservation.safeRoot(root,MemoryRecoveryObservation.Step.CONSUMER_RECOVER_AFTER)||Files.isSymbolicLink(source)
                        ||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))continue;
                byte[] bytes;try(var stream=Files.newInputStream(source)){bytes=stream.readNBytes(MAX_BYTES+1);}
                if(bytes.length>MAX_BYTES){status="OVERSIZE";continue;}
                var value=json().readValue(bytes,Snapshot.class);
                if(value.sample()!=sample)throw new IllegalArgumentException("diagnostic_sample_mismatch");
                status=MemoryRecoveryObservation.write(output.resolve("thread-sample-"+sample+".json"),json().writeValueAsString(value))?"COPIED":"OUTPUT_UNAVAILABLE";
            }catch(Exception invalid){status="UNAVAILABLE_OR_INVALID";}
            finally{
                if(!"COPIED".equals(status))try{
                    MemoryRecoveryObservation.write(output.resolve("thread-sample-"+sample+".json"),json().writeValueAsString(new Snapshot(1,Status.UNAVAILABLE,sample,0,-1,-1,0,List.of(),List.of())));
                }catch(Exception ignored){/* diagnostic output remains unavailable */}
                MemoryRecoveryObservation.write(output.resolve("thread-sample-"+sample+"-status.json"),"{\"status\":\""+status+"\"}");
            }
        }
    }
}
