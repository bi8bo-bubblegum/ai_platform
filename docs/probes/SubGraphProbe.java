import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.*;
import org.bsc.langgraph4j.state.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * 冒烟测试：子图内部的中断（InterruptibleAction）能不能传到父图？
 *
 * 场景：
 *   父图: START -> entry -> child(子图) -> after -> END
 *   子图: START -> subStart -> gate(可中断，恒中断) -> END
 *
 * 期待（如果子图的中断能传到父图）：
 *   - gate 的 apply() 不被执行
 *   - after 不被执行
 *   - 整体抛出可被 GraphInterruptException.of 识别的异常
 */
public class SubGraphProbe {

    public static class S extends AgentState {
        public static final Map<String, Channel<?>> SCHEMA = Map.of(
                "log", Channels.<String>appender(ArrayList::new)
        );
        public S(Map<String, Object> init) {
            super(init);
        }
        public List<String> log() {
            return this.<List<String>>value("log").orElse(List.of());
        }
    }

    /** 子图里的可中断节点：永远要求中断 */
    static class GateNode implements NodeAction<S>, InterruptibleAction<S> {
        @Override
        public Optional<InterruptionMetadata<S>> interrupt(String nodeId, S state, RunnableConfig config) {
            System.out.println("  [子图] gate.interrupt() 被调用 → 返回【有值】(要求停下)");
            return Optional.of(InterruptionMetadata.builder(nodeId, state).reason("probe").build());
        }
        @Override
        public Map<String, Object> apply(S state) {
            System.out.println("  [子图] gate.apply() 执行了  ←← 中断没生效！");
            return Map.of("log", "gate-executed");
        }
    }

    public static void main(String[] args) throws Exception {

        var sub = new StateGraph<>(S.SCHEMA, S::new)
                .addNode("subStart", AsyncNodeAction.node_async(s -> {
                    System.out.println("  [子图] subStart 执行");
                    return Map.of("log", "sub-start");
                }))
                .addNode("gate", AsyncNodeAction.node_async(new GateNode()))
                .addEdge(GraphDefinition.START, "subStart")
                .addEdge("subStart", "gate")
                .addEdge("gate", GraphDefinition.END)
                .compile();

        var parent = new StateGraph<>(S.SCHEMA, S::new)
                .addNode("entry", AsyncNodeAction.node_async(s -> {
                    System.out.println("[父图] entry 执行");
                    return Map.of("log", "entry");
                }))
                .addNode("child", sub)
                .addNode("after", AsyncNodeAction.node_async(s -> {
                    System.out.println("[父图] after 执行");
                    return Map.of("log", "after");
                }))
                .addEdge(GraphDefinition.START, "entry")
                .addEdge("entry", "child")
                .addEdge("child", "after")
                .addEdge("after", GraphDefinition.END)
                .compile();

        var cfg = RunnableConfig.builder().threadId("probe-1").build();

        System.out.println("=== 开始 ===");
        try {
            var r = parent.invoke(GraphInput.args(new HashMap<String, Object>()), cfg);
            System.out.println("\n★ 父图正常返回: " + r.map(S::log).orElse(null));
            System.out.println("★ 结论: 子图中断【没有】传到父图 —— 父图当它是普通节点跑完");
        } catch (Exception e) {
            System.out.println("\n★ 抛出异常: " + e.getClass().getName());
            System.out.println("   message: " + e.getMessage());
            boolean isInterrupt = GraphInterruptException.of(e).isPresent();
            System.out.println("★ GraphInterruptException.of() 能识别: " + isInterrupt);
            System.out.println(isInterrupt
                    ? "★ 结论: 子图中断【能】传到父图 —— 子图方案可行"
                    : "★ 结论: 抛的是普通异常，不是中断语义 —— 子图方案要另想办法");
        }
    }
}
