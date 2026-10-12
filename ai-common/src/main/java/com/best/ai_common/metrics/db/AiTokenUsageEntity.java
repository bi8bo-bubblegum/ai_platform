package com.best.ai_common.metrics.db;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_token_usage")
public class AiTokenUsageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    /** 来源服务名，用于多服务共用一张表后按服务维度统计 */
    private String serviceName;

    private String profile;

    private String modelName;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer totalTokens;

    private LocalDateTime occurredAt;
}
