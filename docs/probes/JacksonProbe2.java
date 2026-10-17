import com.best.ai_orchestrator.core.*;
import com.best.ai_orchestrator.observability.RunEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class JacksonProbe2 {
    public static void main(String[] args) throws Exception {
        // 与 RedisRunStateStore 完全相同的配置（这是被验证过的"恢复契约"）
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        int fail = 0;

        // ---- Budget ----
        Budget b = new Budget(1000, 200, 350);
        Budget b2 = om.readValue(om.writeValueAsString(b), Budget.class);
        fail += check("Budget", b2.getMaxTokens() == 1000 && b2.getPerTaskTokens() == 200
                && b2.getUsedTokens() == 350 && b2.remaining() == 650 && !b2.exhausted());

        // ---- TaskOutcome（record，含 isFailed 附加方法）----
        TaskOutcome ok = TaskOutcome.done("t1", "订单已签收", java.util.List.of("订单表"), 0.9, java.util.List.of(), 120);
        TaskOutcome bad = TaskOutcome.failed("t2", "工具超时", 50);
        TaskOutcome ok2 = om.readValue(om.writeValueAsString(ok), TaskOutcome.class);
        TaskOutcome bad2 = om.readValue(om.writeValueAsString(bad), TaskOutcome.class);
        fail += check("TaskOutcome.done 往返", ok2.taskId().equals("t1") && ok2.summary().equals("订单已签收")
                && ok2.confidence() == 0.9 && !ok2.isFailed() && ok2.tokensUsed() == 120);
        fail += check("TaskOutcome.failed 往返", bad2.taskId().equals("t2") && bad2.error().equals("工具超时")
                && bad2.isFailed());

        // ---- WorkerTurn ----
        WorkerTurn turn = WorkerTurn.toolResult("1 row");
        WorkerTurn call = WorkerTurn.toolCall("mysql__mysql_query", "{\"sql\":\"select 1\"}");
        WorkerTurn turn2 = om.readValue(om.writeValueAsString(turn), WorkerTurn.class);
        WorkerTurn call2 = om.readValue(om.writeValueAsString(call), WorkerTurn.class);
        fail += check("WorkerTurn 往返", turn2.content().equals("1 row")
                && call2.tool().equals("mysql__mysql_query") && call2.args().contains("select 1"));

        // ---- PendingApproval ----
        PendingApproval pa = new PendingApproval("t1", "查订单状态", "mysql__mysql_query",
                "{\"sql\":\"delete from t\"}", com.best.ai_common.mcp.ToolRisk.HIGH, "危险操作");
        PendingApproval pa2 = om.readValue(om.writeValueAsString(pa), PendingApproval.class);
        fail += check("PendingApproval 往返", pa2.taskId().equals("t1") && pa2.risk() == com.best.ai_common.mcp.ToolRisk.HIGH
                && pa2.args().contains("delete from t") && pa2.taskGoal().equals("查订单状态"));

        // ---- RunEvent ----
        RunEvent ev = RunEvent.completed("t1", "完成摘要", 0.8, 120);
        RunEvent ev2 = om.readValue(om.writeValueAsString(ev), RunEvent.class);
        fail += check("RunEvent 往返", ev2.taskId().equals("t1"));

        // ---- TaskNode（含 turns / pending / approved）----
        TaskSpec spec = new TaskSpec("t1", "查订单状态", "一句话结论", "不要猜", "order", java.util.List.of(), 5);
        TaskNode node = new TaskNode(spec);
        node.addTurn(WorkerTurn.toolCall("mysql__mysql_query", "{\"sql\":\"select 1\"}"));
        node.awaitApproval(pa);
        TaskNode node2 = om.readValue(om.writeValueAsString(node), TaskNode.class);
        fail += check("TaskNode 往返", node2.getSpec().goal().equals("查订单状态")
                && node2.getTurns().size() == 1
                && node2.getPending() != null && node2.getPending().tool().equals("mysql__mysql_query")
                && !node2.isDone());

        // ---- 已决定 + 已完成 的 TaskNode ----
        node.decide(true);
        node.finish(ok);
        node.clearDecision();
        TaskNode node3 = om.readValue(om.writeValueAsString(node), TaskNode.class);
        fail += check("TaskNode(已完成) 往返", node3.isDone() && node3.getOutcome().summary().equals("订单已签收")
                && node3.getPending() == null);

        // ---- Plan ----
        Plan plan = new Plan(java.util.List.of(node, node3));
        Plan plan2 = om.readValue(om.writeValueAsString(plan), Plan.class);
        fail += check("Plan 往返", plan2.getNodes().size() == 2 && plan2.doneCount() == 2
                && plan2.outcomes().size() == 2);

        // ---- RunState（总装）----
        RunState st = new RunState("run-1", "帮我查订单",
                RunState.RunContext.of("u1", "s1", "a1"), new Budget(1000, 200, 500));
        st.setPlan(plan);
        st.setTerminationReason("全部完成");
        st.beginRound();
        st.endRound();
        String json = om.writeValueAsString(st);
        RunState st2 = om.readValue(json, RunState.class);
        fail += check("RunState 往返", st2.getRunId().equals("run-1") && st2.getRequest().equals("帮我查订单")
                && st2.getContext().userId().equals("u1")
                && st2.getBudget().getMaxTokens() == 1000 && st2.getBudget().getUsedTokens() == 500
                && st2.getPlan().getNodes().size() == 2 && st2.getPlan().doneCount() == 2
                && st2.getRounds() == 1 && "全部完成".equals(st2.getTerminationReason()));

        // ---- RunReport（必须带 events 出去 —— 否则事件没有出口）----
        // st 上先补两个事件再 report
        st.addEvent(RunEvent.dispatched("t1", "查订单状态"));
        st.addEvent(RunEvent.completed("t1", "已签收", 0.9, 120));
        RunState.RunReport rep = st.report();
        RunState.RunReport rep2 = om.readValue(om.writeValueAsString(rep), RunState.RunReport.class);
        fail += check("RunReport 往返(带 events)", rep2.runId().equals("run-1")
                && rep2.tasksDone() == 2 && rep2.events().size() == 2
                && rep2.events().get(0).kind() == RunEvent.Kind.TASK_DISPATCHED
                && rep2.events().get(1).tokens() == 120);
        fail += check("report() 的 events 是快照不是活引用", rep.events() != st.getEvents());
        fail += check("RunState.getPlan().nodes 可再序列化", om.readValue(
                om.writeValueAsString(st2.getPlan()), Plan.class).doneCount() == 2);

        System.out.println(fail == 0 ? "\n★★★ 全部通过 ★★★" : "\n失败 " + fail + " 项");
    }

    static int check(String name, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  ✗ ") + name);
        return ok ? 0 : 1;
    }
}
