package com.best.ai_common.metrics.db;

import com.best.ai_common.context.AiUserContext;
import com.best.ai_common.metrics.TokenUsageRecord;
import com.best.ai_common.metrics.TokenUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DbTokenUsageRecorder implements TokenUsageRecorder, DisposableBean {

    /** 队列容量：持续积压到该值说明落库跟不上，需要告警 */
    private static final int QUEUE_CAPACITY = 10_000;

    /** 单批最大条数 */
    private static final int BATCH_SIZE = 200;

    /** 队列空闲时的等待间隔，避免空转 */
    private static final long IDLE_WAIT_MS = 1000L;

    private final AiTokenUsageMapper mapper;

    private final BlockingQueue<TokenUsageRecord> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final Thread worker;

    private volatile boolean running = true;

    public DbTokenUsageRecorder(AiTokenUsageMapper mapper) {
        this.mapper = mapper;
        this.worker = new Thread(this::consume, "token-usage-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void record(TokenUsageRecord record) {
        if (!queue.offer(record)) {
            // 队列满说明落库严重滞后：丢弃并告警，保证不影响业务请求
            log.warn("token 用量队列已满({})，丢弃记录: userId={} profile={} totalTokens={}",
                    QUEUE_CAPACITY, record.userId(), record.profile(), record.totalTokens());
        }
    }

    private void consume() {
        List<TokenUsageRecord> batch = new ArrayList<>(BATCH_SIZE);
        while (running) {
            try {
                // 阻塞等第一条，有数据才继续攒批
                TokenUsageRecord first = queue.poll(IDLE_WAIT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                // 把队列中已排队的记录一并取出，凑成一批
                queue.drainTo(batch, BATCH_SIZE - 1);

                writeBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 落库失败不能拖垮计量线程，也不能影响业务
                log.error("token 用量入库失败，丢弃 {} 条", batch.size(), e);
            } finally {
                batch.clear();
            }
        }
    }

    private void writeBatch(List<TokenUsageRecord> records){
        if(records.isEmpty()){
            return;
        }
        mapper.insertBatch(records.stream().map(DbTokenUsageRecorder::toEntity).toList());
    }

    @Override
    public void destroy() throws Exception {
        running = false;
        worker.join(5_000);

        List<TokenUsageRecord> remaining = new ArrayList<>(BATCH_SIZE);
        queue.drainTo(remaining);
        if (remaining.isEmpty()) {
            return;
        }
        try {
            writeBatch(remaining);
            log.info("退出前刷写 token 用量 {} 条", remaining.size());
        } catch (Exception e) {
            log.error("退出前刷写 token 用量失败，丢失 {} 条", remaining.size(), e);
        }
    }

    private static AiTokenUsageEntity toEntity(TokenUsageRecord record) {
        AiTokenUsageEntity entity = new AiTokenUsageEntity();
        // 兜底：保证 user_id 不为 null，否则按用户查询会漏数据
        entity.setUserId(record.userId() == null ? AiUserContext.ANONYMOUS : record.userId());
        // 兜底：保证 service_name 不为 null，否则按服务统计会漏数据
        entity.setServiceName(record.serviceName() == null ? TokenUsageRecord.UNKNOWN_SERVICE : record.serviceName());
        entity.setProfile(record.profile());
        entity.setModelName(record.modelName());
        entity.setInputTokens(record.inputTokens());
        entity.setOutputTokens(record.outputTokens());
        entity.setTotalTokens(record.totalTokens());
        entity.setOccurredAt(LocalDateTime.ofInstant(record.occurredAt(), ZoneId.systemDefault()));
        return entity;
    }
}
