package com.best.ai_memory.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.best.ai_memory.user.db.FloatArrayTypeHandler;
import com.fasterxml.jackson.databind.ser.std.StdArraySerializers;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "ai_user_memory", autoResultMap = true)
public class UserMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

   private String userId;

   private String content;

   @TableField(typeHandler = FloatArrayTypeHandler.class)
   private float[] embedding;

   private String sourceSession;

   private String sourceAssistant;

   private Instant createdAt;

    public static UserMemory of(String assistantId, String userId, String content, String sourceSession){
        UserMemory userMemory = new UserMemory();
        userMemory.setSourceAssistant(assistantId);
        userMemory.setUserId(userId);
        userMemory.setContent(content);
        userMemory.setSourceSession(sourceSession);
        userMemory.setCreatedAt(Instant.now());
        return userMemory;
    }
}
