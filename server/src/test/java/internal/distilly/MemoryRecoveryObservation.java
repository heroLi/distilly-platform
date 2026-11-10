package internal.distilly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/** Test-only, fixed enums and relative monotonic numbers. Never reads process logs or environment. */
final class MemoryRecoveryObservation {
    enum Step { PREPARE, RECOVER, CONSUMER_PREPARE, CONSUMER_RECOVER_BEFORE, CONSUMER_RECOVER_AFTER }
    enum Stage {
        MAIN_ENTERED, SPRING_START_BEGIN, SPRING_READY, PREPARATION_BEGIN, PREPARATION_END,
        RECOVERY_ASSERTIONS_BEGIN, RECOVERY_ASSERTIONS_END, RESULT_WRITE_BEGIN, RESULT_WRITE_END,
        WAITING_FOR_FORCE_KILL, CONTEXT_CLOSE_BEGIN, CONTEXT_CLOSE_END, CONTEXT_CLOSE_FAILED,
        FIXTURE_COMPLETE, FIXTURE_FAILED
    }
    enum Outcome { READY, COMPLETED, DEADLINE_EXCEEDED, ABORTED }
    @FunctionalInterface interface Sink { void append(String line) throws IOException; }
    static final int MAX_BYTES=16*1024;

    static final class Recorder implements AutoCloseable {
        static final int MAX_EVENTS=64;
        private final LongSupplier clock;
        private final Sink sink;
        private final long started;
        private final ConcurrentLinkedQueue<String> queued=new ConcurrentLinkedQueue<>();
        private final AtomicInteger accepted=new AtomicInteger(),pending=new AtomicInteger(),failures=new AtomicInteger(),dropped=new AtomicInteger();
        private volatile boolean closed;
        private final Thread writer;
        Recorder(LongSupplier clock,Sink sink) {
            this.clock=clock;this.sink=sink;this.started=clock.getAsLong();
            Thread created=null;
            if(sink!=null)try{
                created=new Thread(this::writeLoop,"memory-test-phase-writer");created.setDaemon(true);created.start();
            }catch(RuntimeException unavailable){failures.incrementAndGet();closed=true;created=null;}
            this.writer=created;
        }
        void mark(Stage stage) {
            if(sink==null)return;
            pending.incrementAndGet();boolean enqueued=false;
            try{
                if(closed){dropped.incrementAndGet();return;}
                int count;
                do{count=accepted.get();if(count>=MAX_EVENTS){dropped.incrementAndGet();return;}}
                while(!accepted.compareAndSet(count,count+1));
                long elapsed=clock.getAsLong()-started;
                queued.offer(stage.name()+"\t"+elapsed+"\n");enqueued=true;
            }finally{
                if(!enqueued)pending.decrementAndGet();
                if(writer!=null)LockSupport.unpark(writer);
            }
        }
        private void writeLoop(){
            for(;;){
                String line=queued.poll();
                if(line==null){
                    if(closed&&pending.get()==0)return;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));continue;
                }
                try{sink.append(line);}
                catch(IOException|RuntimeException failure){failures.incrementAndGet();} // Never expose exception messages.
                finally{pending.decrementAndGet();}
            }
        }
        int writeFailures(){return failures.get();}
        int pendingEvents(){return pending.get();}
        int droppedEvents(){return dropped.get();}
        boolean writerAlive(){return writer!=null&&writer.isAlive();}
        @Override public void close(){closed=true;if(writer!=null)LockSupport.unpark(writer);} // No join, flush or sink cancellation on caller.
        AutoCloseable closing(AutoCloseable resource) {
            return ()->{
                mark(Stage.CONTEXT_CLOSE_BEGIN);
                try { resource.close(); }
                catch(Exception|Error failure){mark(Stage.CONTEXT_CLOSE_FAILED);throw failure;}
                mark(Stage.CONTEXT_CLOSE_END);
            };
        }
    }
    static Recorder recorder(Path root,String step) {
        Step parsed;
        try { parsed=Step.valueOf(step.toUpperCase(Locale.ROOT)); }
        catch(IllegalArgumentException failure){return new Recorder(System::nanoTime,null);}
        if(!safeRoot(root,parsed))return new Recorder(System::nanoTime,null);
        Path file=root.resolve(step+"-progress.tsv");
        if(Files.isSymbolicLink(file))return new Recorder(System::nanoTime,null);
        var recorder=new Recorder(System::nanoTime,line->Files.writeString(file,line,StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,StandardOpenOption.APPEND));
        recorder.mark(Stage.MAIN_ENTERED);return recorder;
    }
    record Event(Stage stage,long elapsedNanos) {}
    record Trace(String status,List<Event> events) {}
    static boolean safeRoot(Path root,Step step) {
        try {
        String prefix=step.name().startsWith("CONSUMER_")?"distilly-memory-consumer-process-":"distilly-memory-sync-process-";
        if(root.getFileName()==null||!root.getFileName().toString().startsWith(prefix))return false;
        for(Path p=root.toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))return false;
        return true;
        }catch(SecurityException denied){return false;}
    }
    static Trace read(Path root,Step step) {
        Path file=root.resolve(step.name().toLowerCase(Locale.ROOT)+"-progress.tsv");
        if(!safeRoot(root,step)||Files.isSymbolicLink(file)
                ||file.toAbsolutePath().getParent()==null)return new Trace("REFUSED_PATH",List.of());
        for(Path parent=file.toAbsolutePath().getParent();parent!=null;parent=parent.getParent())
            if(Files.isSymbolicLink(parent))return new Trace("REFUSED_PATH",List.of());
        byte[] bytes;
        try(var stream=Files.newInputStream(file)){bytes=stream.readNBytes(MAX_BYTES+1);}
        catch(IOException failure){return new Trace("UNAVAILABLE",List.of());}
        if(bytes.length>MAX_BYTES)return new Trace("OVERSIZE",List.of());
        String text=new String(bytes,StandardCharsets.UTF_8);
        boolean partial=!text.isEmpty()&&!text.endsWith("\n");
        if(partial)text=text.substring(0,text.lastIndexOf('\n')+1);
        var events=new ArrayList<Event>();long previous=-1;
        for(String line:text.split("\n")){
            if(line.isEmpty())continue;
            try{
                String[] fields=line.split("\t",-1);
                if(fields.length!=2||!fields[1].matches("[0-9]{1,19}"))return new Trace("INVALID_TRACE",List.of());
                var stage=Stage.valueOf(fields[0]);long elapsed=Long.parseLong(fields[1]);
                if(elapsed<previous||events.size()>=64)return new Trace("INVALID_TRACE",List.of());
                events.add(new Event(stage,elapsed));previous=elapsed;
            }catch(IllegalArgumentException failure){return new Trace("INVALID_TRACE",List.of());}
        }
        return new Trace(partial?"PARTIAL_WRITE":"READ",List.copyOf(events));
    }
    private static String events(Trace trace) {
        var rows=new ArrayList<String>();long previous=0;
        for(var event:trace.events()){
            rows.add("{\"stage\":\""+event.stage()+"\",\"elapsed_nanos\":"+event.elapsedNanos()
                    +",\"delta_nanos\":"+(event.elapsedNanos()-previous)+"}");previous=event.elapsedNanos();
        }
        return "["+String.join(",",rows)+"]";
    }
    static boolean write(Path output,String value) {
        try{
            if(Files.isSymbolicLink(output))return false;
            for(Path parent=output.toAbsolutePath().getParent();parent!=null;parent=parent.getParent())
                if(Files.isSymbolicLink(parent))return false;
            Path temporary=output.resolveSibling(output.getFileName()+".tmp");
            if(Files.isSymbolicLink(temporary))return false;
            Files.writeString(temporary,value+"\n",StandardCharsets.UTF_8);
            Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return true;
        }catch(IOException|SecurityException failure){return false;}
    }
    static boolean capture(Path root,Step step,Process process,long parentStarted,Outcome outcome,Path output) {
        // Freeze the alive state and trace before termination; shutdown output is never reinterpreted.
        long elapsed=System.nanoTime()-parentStarted;boolean alive=process.isAlive();
        Integer exit=alive?null:process.exitValue();Trace trace=read(root,step);
        String family=step.name().startsWith("CONSUMER_")?"memory-sync-consumer-process":"memory-sync-process";
        return write(output,"{\"fixture_path_id\":\"synthetic/"+family+"/"+step.name().toLowerCase(Locale.ROOT)
                +"\",\"status\":\""+outcome+"\",\"deadline_seconds\":30,\"observed_before_stop\":true,"
                +"\"parent_elapsed_nanos\":"+elapsed+",\"process_alive\":"+alive+",\"exit_code\":"+exit
                +",\"clock_domains\":\"child_since_main_and_parent_since_launch_are_separate\",\"phase_delivery\":\"ASYNC_BOUNDED_BEST_EFFORT\",\"trace_may_be_prefix\":true,\"trace_status\":\""
                +trace.status()+"\",\"events\":"+events(trace)+"}");
    }
    static void stop(Path root,Step step,Process process,long parentStarted,boolean captured,Path before,Path after)
            throws InterruptedException {
        if(process==null)return;
        if(!captured)capture(root,step,process,parentStarted,Outcome.ABORTED,before);
        try{
            if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
        }finally{
            boolean alive=process.isAlive();Integer exit=alive?null:process.exitValue();
            write(after,"{\"status\":\""+(alive?"PROCESS_STILL_ALIVE":"CLEANED")+"\",\"parent_elapsed_nanos\":"
                    +(System.nanoTime()-parentStarted)+",\"process_alive\":"+alive+",\"exit_code\":"+exit+"}");
        }
    }
}
