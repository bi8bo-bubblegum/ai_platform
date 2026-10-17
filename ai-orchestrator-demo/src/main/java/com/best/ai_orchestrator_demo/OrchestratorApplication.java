package com.best.ai_orchestrator_demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 编排器的示例应用（验收台）。
 *
 * <p>它存在的意义是<b>让框架能被跑起来验证</b>：角色表、HTTP 入口、
 * 配置都在这个模块里；{@code ai-orchestrator} 本身是库，一行业务都不认识。
 *
 * <p>将来第一个真实助手出现时，这个模块的形态就是它的起点 ——
 * 复制它、改 {@code Roles} 枚举和三个 rules 类、改 yml 里的 token。
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
