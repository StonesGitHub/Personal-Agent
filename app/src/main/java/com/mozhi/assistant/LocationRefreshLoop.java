package com.mozhi.assistant;

/** One bounded acquisition at a time; the Activity controls its foreground lifetime. */
final class LocationRefreshLoop {
    static final long INTERVAL_MS=60_000;
    interface Driver {
        void refresh(Runnable complete);
        void schedule(Runnable task,long delay);
        void cancel(Runnable task);
    }
    private final Driver driver;
    private boolean running;
    private long generation,request;
    private Runnable scheduled;

    LocationRefreshLoop(Driver driver){this.driver=driver;}
    void start(){if(running)return;running=true;refresh(++generation);}
    void stop(){
        running=false;generation++;
        if(scheduled!=null){driver.cancel(scheduled);scheduled=null;}
    }
    private void refresh(long token){
        if(!running||token!=generation)return;
        scheduled=null;long id=++request;
        driver.refresh(()->{
            if(!running||token!=generation||id!=request||scheduled!=null)return;
            scheduled=()->refresh(token);driver.schedule(scheduled,INTERVAL_MS);
        });
    }
}
