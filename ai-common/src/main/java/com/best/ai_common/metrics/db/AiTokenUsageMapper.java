package com.best.ai_common.metrics.db;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AiTokenUsageMapper extends BaseMapper<AiTokenUsageEntity> {


    int insertBatch(@Param("list")List<AiTokenUsageEntity> list);

    List<UserTokenUsage> sumByUser(@Param("start")LocalDateTime start, @Param("end") LocalDateTime end);
}
