package com.mozhi.assistant;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LocationRefreshLoopTest {
    private static final class Fake implements LocationRefreshLoop.Driver {
        long now;int calls;boolean available=true;
        final List<Runnable> completions=new ArrayList<>();
        final Map<Runnable,Long> tasks=new LinkedHashMap<>();
        final LocationRefreshLoop loop=new LocationRefreshLoop(this);
        public void refresh(Runnable complete){calls++;completions.add(complete);if(!available)complete.run();}
        public void schedule(Runnable task,long delay){assertTrue(delay>0);tasks.put(task,now+delay);}
        public void cancel(Runnable task){tasks.remove(task);}
        void finish(){completions.get(completions.size()-1).run();}
        void advance(long ms){
            long until=now+ms;
            while(true){
                Map.Entry<Runnable,Long> next=tasks.entrySet().stream().min(Map.Entry.comparingByValue()).orElse(null);
                if(next==null||next.getValue()>until)break;
                now=next.getValue();Runnable task=next.getKey();tasks.remove(task);task.run();
            }
            now=until;
        }
    }
    @Test public void foregroundStartIsImmediateAndRepeatedRenderingDoesNotDuplicateIt(){
        Fake f=new Fake();f.loop.start();f.loop.start();f.loop.start();assertEquals(1,f.calls);assertTrue(f.tasks.isEmpty());
        f.finish();f.loop.start();assertEquals(1,f.tasks.size());assertEquals(1,f.calls);
    }
    @Test public void eachCompletedAcquisitionSchedulesAnotherWithoutUserAction(){
        Fake f=new Fake();f.loop.start();f.finish();f.advance(59_999);assertEquals(1,f.calls);
        f.advance(1);assertEquals(2,f.calls);f.finish();f.advance(60_000);assertEquals(3,f.calls);
    }
    @Test public void aSlowAcquisitionNeverOverlapsAnother(){
        Fake f=new Fake();f.loop.start();f.advance(180_000);assertEquals(1,f.calls);assertTrue(f.tasks.isEmpty());
        f.finish();f.advance(60_000);assertEquals(2,f.calls);
    }
    @Test public void backgroundOrOffCancelsScheduledRefresh(){
        Fake f=new Fake();f.loop.start();f.finish();Runnable removed=f.tasks.keySet().iterator().next();
        f.loop.stop();f.advance(180_000);removed.run();assertEquals(1,f.calls);assertTrue(f.tasks.isEmpty());
    }
    @Test public void lateLocationAfterStoppingCannotRestartTheLoop(){
        Fake f=new Fake();f.loop.start();f.loop.stop();f.finish();f.advance(180_000);assertEquals(1,f.calls);assertTrue(f.tasks.isEmpty());
    }
    @Test public void resumeStartsImmediatelyAndIgnoresPriorCompletion(){
        Fake f=new Fake();f.loop.start();Runnable old=f.completions.get(0);f.loop.stop();f.loop.start();assertEquals(2,f.calls);
        old.run();assertTrue(f.tasks.isEmpty());f.finish();f.advance(60_000);assertEquals(3,f.calls);
        old.run();assertTrue(f.tasks.isEmpty());
    }
    @Test public void duplicateCompletionCannotScheduleExtraRefresh(){
        Fake f=new Fake();f.loop.start();Runnable first=f.completions.get(0);first.run();first.run();assertEquals(1,f.tasks.size());
        f.advance(60_000);first.run();assertEquals(2,f.calls);assertTrue(f.tasks.isEmpty());
    }
    @Test public void unavailableLocationRetriesWithoutBusyLoopAndRecovers(){
        Fake f=new Fake();f.available=false;f.loop.start();assertEquals(1,f.calls);assertEquals(1,f.tasks.size());
        f.advance(120_000);assertEquals(3,f.calls);f.available=true;f.advance(60_000);assertEquals(4,f.calls);
        assertTrue(f.tasks.isEmpty());f.finish();f.loop.stop();f.advance(120_000);assertEquals(4,f.calls);
    }
}
