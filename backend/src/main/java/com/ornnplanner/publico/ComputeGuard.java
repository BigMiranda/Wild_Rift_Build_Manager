package com.ornnplanner.publico;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs a calculation of an endpoint. In the local app it simply runs it. In the public version it runs on a small fixed
 * set of workers ({@code planner.public.workers}) and waits at most {@code planner.public.compute-seconds}: when every
 * worker is busy or the time is up, the visitor gets 503 "tente mais tarde". A calculation past its time is interrupted;
 * the one that does not stop at once keeps its worker until it ends, so the server never runs more than the workers.
 */
@Component
public class ComputeGuard {

    public static final String BUSY = "O servidor está ocupado: tente mais tarde.";
    public static final String TOO_LONG = "O cálculo passou do tempo da versão pública: tente mais tarde.";

    private final PublicMode mode;
    private final PublicLimits limits;
    private final ThreadPoolExecutor pool;

    public ComputeGuard(PublicMode mode, PublicLimits limits) {
        this.mode = mode;
        this.limits = limits;
        int n = Math.max(1, limits.getWorkers());
        // no queue: a calculation either gets a worker now or is refused
        this.pool = mode.on() ? new ThreadPoolExecutor(n, n, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "public-compute");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    public <T> T run(Supplier<T> work) {
        if (pool == null) {
            return work.get();
        }
        Future<T> f;
        try {
            f = pool.submit(work::get);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, BUSY);
        }
        try {
            return f.get(Math.max(1, limits.getComputeSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, TOO_LONG);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, BUSY);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw new IllegalStateException(c);
        }
    }

    public boolean publicMode() {
        return mode.on();
    }

    @PreDestroy
    void stop() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
