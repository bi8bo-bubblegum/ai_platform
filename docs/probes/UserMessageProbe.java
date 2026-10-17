import com.best.ai_orchestrator.orchestrator.Synthesizer;
import com.best.ai_orchestrator.worker.WorkerAgent;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 验证 @UserMessage 修复：调一次 turn() / synthesize()，
 * 期望异常【不是】IllegalConfigurationException（那说明配置校验已过，
 * 剩下的是连不上假端点的网络错 —— 修复前它会先抛配置错）。
 */
public class UserMessageProbe {
    public static void main(String[] args) {
        OpenAiChatModel fake = OpenAiChatModel.builder()
                .baseUrl("http://127.0.0.1:1")   // 必然连不上
                .apiKey("k").modelName("m")
                .build();

        int bad = 0;
        for (Object[] c : new Object[][]{
                {"WorkerAgent.turn", (Runnable) () -> {
                    var a = AiServices.builder(WorkerAgent.class).chatModel(fake).build();
                    a.turn("r", "rules", "task", "tools", "mem", "hist");
                }},
                {"Synthesizer.synthesize", (Runnable) () -> {
                    var a = AiServices.builder(Synthesizer.class).chatModel(fake).build();
                    a.synthesize("req", "conv", "outcomes");
                }}}) {
            String name = (String) c[0];
            try {
                ((Runnable) c[1]).run();
                System.out.println("  ? " + name + " 没抛异常（意外）");
                bad++;
            } catch (Throwable t) {
                String cn = t.getClass().getSimpleName();
                if (cn.contains("IllegalConfiguration")) {
                    System.out.println("  ✗ " + name + " 仍抛 " + cn + ": " + t.getMessage());
                    bad++;
                } else {
                    System.out.println("  ✓ " + name + " 过了配置校验（最终报 " + cn + "，符合预期）");
                }
            }
        }
        System.out.println(bad == 0 ? "\n★★★ @UserMessage 修复生效 ★★★" : "\n失败 " + bad + " 项");
    }
}
