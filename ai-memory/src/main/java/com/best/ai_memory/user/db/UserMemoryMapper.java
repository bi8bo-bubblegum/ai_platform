package com.best.ai_memory.user.db;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.best.ai_memory.user.UserMemory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserMemoryMapper extends BaseMapper<UserMemory> {

    List<UserMemory> selectByUserId(@Param("userId") String userId, @Param("limit") int limit);

    List<Long> selectOldestIds(@Param("userId") String userId, @Param("count") int count);
}
