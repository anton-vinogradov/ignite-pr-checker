package com.github.igniteprchecker.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Runs independent tasks on the shared pool and gathers their results, preserving order. */
final class Parallel {
    private Parallel() {
    }

    static <T> List<T> run(ExecutorService executor, List<? extends Callable<T>> tasks) {
        if (tasks.isEmpty())
            return List.of();

        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> out = new ArrayList<>(futures.size());
            for (Future<T> f : futures)
                out.add(f.get());

            return out;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("analysis interrupted", e);
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re)
                throw re;

            throw new IllegalStateException("analysis task failed", e.getCause());
        }
    }
}
