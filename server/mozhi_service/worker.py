import asyncio
import fcntl
import signal
from .settings import Settings
from .store import Store
from .runtime import Runtime
from .personal import PersonalStore


async def serve(store, runtime):
    stop = asyncio.Event()
    active = None
    loop = asyncio.get_running_loop()

    def shutdown():
        stop.set()
        if active is not None:
            active.cancel()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, shutdown)
    store.recover()

    async def heartbeat():
        while not stop.is_set():
            store.heartbeat()
            PersonalStore(store).tick()
            try:
                await asyncio.wait_for(stop.wait(), timeout=5)
            except TimeoutError:
                pass

    heartbeat_task = asyncio.create_task(heartbeat())
    try:
        while not stop.is_set():
            rid = store.claim()
            if rid:
                active = asyncio.create_task(runtime.execute(rid))
                try:
                    await active
                except asyncio.CancelledError:
                    store.finish(rid, "interrupted", error="执行进程停止")
                active = None
            else:
                try:
                    await asyncio.wait_for(stop.wait(), timeout=0.5)
                except TimeoutError:
                    pass
    finally:
        stop.set()
        await heartbeat_task


def main():
    settings = Settings.env()
    store = Store(settings.db_path, settings.model)
    # An OS lock prevents a second worker and is released even after SIGKILL.
    with open(settings.db_path + ".worker.lock", "a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit("Only one Mozhi worker may use this database") from None
        asyncio.run(serve(store, Runtime(store, settings)))


if __name__ == "__main__":
    main()
