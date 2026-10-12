package com.best.ai_common.mcp.db;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tools")
public class McpToolEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String effectiveName;

    private String risk;

    private Boolean available;

    private LocalDateTime updatedAt;
}
