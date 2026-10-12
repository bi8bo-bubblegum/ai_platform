package com.best.ai_memory.user.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.best.ai_memory.user.UserMemory;
import com.best.ai_memory.user.UserMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class DbUserMemoryStore implements UserMemoryStore {

    private final UserMemoryMapper userMemoryMapper;

    @Override
    public void insert(UserMemory memory) {
        userMemoryMapper.insert(memory);
    }

    @Override
    public List<UserMemory> findByUserId(String userId, int limit) {
        return userMemoryMapper.selectByUserId(userId, limit);
    }

    @Override
    public int countByUserId(String userId) {
        Long count = userMemoryMapper.selectCount(
                new LambdaQueryWrapper<UserMemory>().eq(UserMemory::getUserId, userId)
        );
        return count == null ? 0 : count.intValue();
    }

    @Override
    public void deleteOldest(String userId, int count) {
        if(count <= 0){
            return;
        }
        List<Long> ids = userMemoryMapper.selectOldestIds(userId, count);
        if (ids.isEmpty()){
            return;
        }
        int deleted = userMemoryMapper.delete(new LambdaQueryWrapper<UserMemory>().in(UserMemory::getId, ids));
        if(deleted != ids.size()){
            log.warn("用户{}的旧记忆清理数量不符：预期{}，实际{}", userId, ids.size(), deleted);
        }
    }

    @Override
    public void deleteAll(String userId) {
        userMemoryMapper.delete(new LambdaQueryWrapper<UserMemory>().eq(UserMemory::getUserId, userId));
    }
}
