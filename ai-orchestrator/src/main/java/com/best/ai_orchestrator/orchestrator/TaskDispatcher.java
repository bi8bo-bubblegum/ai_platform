package com.best.ai_orchestrator.orchestrator;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_orchestrator.core.PendingApproval;
import com.best.ai_orchestrator.core.RunState;
import com.best.ai_orchestrator.core.TaskNode;
import com.best.ai_orchestrator.worker.WorkerExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 派发器 —— 决定"这一批是并行还是顺序"，并把结果收回来。
 *
 * <p><b>只有依赖已满足的子任务才会同时出现在一批里</b> —— 这是并行的前置条件，
 * 也是对"两个子任务实际上互相依赖却被并行出去"（生产环境最常见的翻车方式）的正面回应。
 *
 * <p>★ <b>线程池刻意是有界的 + CallerRunsPolicy</b>：
 * 无界队列会让积压变成<b>静默延迟</b>（任务越排越久，但没有任何报错），
 * 有界队列 + 让提交线程自己干，才能把积压变成<b>可见的降速</b>。
 * 这是本项目 33 号记录过的教训。
 */
@Slf4j
public class TaskDispatcher {

    private final WorkerExecutor executor;
    private final ExecutorService pool;
    private final int maxParallel;

    public TaskDispatcher(WorkerExecutor executor, int maxParallel) {
        this.executor = executor;
        this.maxParallel = Math.max(1, maxParallel);
        this.pool = new ThreadPoolExecutor(
                this.maxParallel,
                this.maxParallel,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.maxParallel),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public sealed interface DispatchResult {
        record AllDone() implements DispatchResult {
        }

        record Paused(PendingApproval pending) implements DispatchResult {
        }
    }

    public DispatchResult dispatch(List<TaskNode> batch, RunState state) {
        if (batch.isEmpty()) {
            return new DispatchResult.AllDone();
        }
        if (batch.size() == 1 || maxParallel == 1) {
            // 顺序：一个做完再下一个。好处是第一个需要审批时立刻停，后面的不白跑
            for (TaskNode node : batch) {
                WorkerExecutor.Execution e = executor.execute(node, state);
                if (e instanceof WorkerExecutor.Execution.AwaitingApproval a) {
                    return new DispatchResult.Paused(a.pending());
                }
                node.finish(((WorkerExecutor.Execution.Finished) e).outcome());
            }
            return new DispatchResult.AllDone();
        }

        // 并行：先全部提交，再逐个收结果
        List<CompletableFuture<WorkerExecutor.Execution>> futures = new ArrayList<>();
        for (TaskNode node : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> runOne(node, state), pool));
        }

        // ★ 先把所有 Finished 落库，再返回第一个 AwaitingApproval。
        //   顺序反过来会丢掉同批里其他子任务已经做完的结果。
        PendingApproval firstPending = null;
        for (int i = 0; i < batch.size(); i++) {
            WorkerExecutor.Execution e = get(futures.get(i), batch.get(i));
            if (e instanceof WorkerExecutor.Execution.AwaitingApproval a) {
                if (firstPending == null) {
                    firstPending = a.pending();
                }
                continue;
            }
            batch.get(i).finish(((WorkerExecutor.Execution.Finished) e).outcome());
        }
        return firstPending == null ? new DispatchResult.AllDone() : new DispatchResult.Paused(firstPending);
    }

    /**
     * 在池线程里跑一个子任务。
     *
     * <p>★ <b>必须自己开 {@link AiUserContext}</b>：ThreadLocal 不会跨线程继承。
     * 漏了这行的症状是"并行时 token 台账里的 userId 是 anonymous" ——
     * 而顺序执行时完全正常，所以只在开启并行后才暴露。
     */
    private WorkerExecutor.Execution runOne(TaskNode node, RunState state) {
        try (var ignored = AiUserContext.open(state.getContext().userId())) {
            return executor.execute(node, state);
        } catch (Exception e) {
            log.error("子任务 {} 执行异常", node.getSpec().id(), e);
            return new WorkerExecutor.Execution.Finished(
                    com.best.ai_orchestrator.core.TaskOutcome.failed(node.getSpec().id(),
                            "执行异常: " + e.getMessage(), 0));
        }
    }

    private WorkerExecutor.Execution get(CompletableFuture<WorkerExecutor.Execution> future, TaskNode node) {
        try {
            return future.get();
        } catch (Exception e) {
            log.error("子任务 {} 等待结果失败", node.getSpec().id(), e);
            return new WorkerExecutor.Execution.Finished(
                    com.best.ai_orchestrator.core.TaskOutcome.failed(node.getSpec().id(),
                            "等待结果失败: " + e.getMessage(), 0));
        }
    }

    /** 优雅停机。池是应用级的，不需要频繁创建销毁 */
    public void shutdown() {
        pool.shutdown();
    }
}
