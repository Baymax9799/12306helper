package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 任务线程池的管理者：起停、查询、进程退出时收尾。 */
public final class TaskEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TaskEngine.class);

    private final TaskRunner.Deps deps;
    private final Map<Long, TaskRunner> runners = new ConcurrentHashMap<>();

    public TaskEngine(TaskRunner.Deps deps) {
        this.deps = deps;
    }

    public synchronized boolean start(Store.TaskRow task) {
        if ("DONE".equals(task.status())) {
            return false;
        }
        stop(task.id());
        deps.store().updateTaskStatus(task.id(), "RUNNING");
        Store.TaskRow fresh = deps.store().findTask(task.id()).orElse(task);
        TaskRunner runner = new TaskRunner(fresh, deps);
        runners.put(task.id(), runner);
        runner.start();
        log.info("任务 {} 已启动", task.id());
        return true;
    }

    public synchronized void stop(long taskId) {
        TaskRunner runner = runners.remove(taskId);
        if (runner != null) {
            runner.stop();
        }
        deps.store().findTask(taskId).ifPresent(t -> {
            if ("RUNNING".equals(t.status()) || "HELD".equals(t.status())) {
                deps.store().updateTaskStatus(taskId, "PAUSED");
            }
        });
    }

    public synchronized void stopAll() {
        for (Long id : Set.copyOf(runners.keySet())) {
            stop(id);
        }
    }

    public boolean isRunning(long taskId) {
        TaskRunner runner = runners.get(taskId);
        return runner != null && runner.isRunning();
    }

    public Set<Long> running() {
        return Set.copyOf(runners.keySet());
    }

    /** 进程启动时把上次残留的 RUNNING 归零，避免重启后立刻偷偷开抢。 */
    public void markStaleTasksPaused() {
        for (Store.TaskRow task : deps.store().findAllTasks()) {
            if ("RUNNING".equals(task.status())) {
                deps.store().updateTaskStatus(task.id(), "PAUSED");
            }
        }
    }

    @Override
    public void close() {
        stopAll();
    }
}
