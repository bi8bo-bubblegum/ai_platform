package com.best.ai_agentscope.ch04_tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class LabTools {

    @Tool(name = "get_weather", description = "查询指定城市的当前天气")
    public String getWeather(
            @ToolParam(name = "city", required = true, description = "城市名，如 北京") String city){
        return city + ": 晴，26°C";
    }

    @Tool(name = "echo", description = "只读回显", readOnly = true, concurrencySafe = true)
    public String echo(@ToolParam(name = "text", required = true, description = "要回显的文本") String text){
        return text;
    }

    @Tool(name = "boom", description = "必然抛异常的工具，验证错误怎么回给模型")
    public String boom(){
        throw new IllegalArgumentException("工具内部炸了");
    }
}
