package org.gitee.jmeter.ai.agent.tools.ipc;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.instance.DelegationGuard;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.gitee.jmeter.ai.ipc.IpcClient;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.gitee.jmeter.ai.utils.AiConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 把一个任务委派给本机另一个 JMeter AI 实例,阻塞等待其 Agent 回合的回复(阻塞式跨进程 RPC)。
 *
 * <p><b>目标寻址:按 {@code instanceId} 或 {@code jmxPath}(互斥)</b>
 * <ol>
 *   <li>给 {@code instanceId} → 精确匹配;</li>
 *   <li>给 {@code jmxPath} → 匹配持有该脚本的存活实例,多个则按最近 {@code startedAt} 确定性择一;</li>
 *   <li>都缺 → 明确错误。</li>
 * </ol>
 *
 * <p><b>目标约束:</b>经 {@link InstanceRegistry#listInstances} 的 TCP+PID 双确认存活过滤;
 * 禁止委派给自身({@code instanceId} 或寻址到本实例必拒)。
 * <p><b>阻塞与超时:</b>复用 {@code jmeter.ai.ipc.agent.timeout.ms}
 * (加 5s 宽限,使目标侧 504+自取消 先于本端 HTTP 超时到达);运行于工具执行线程,不阻塞 EDT(6.4)。
 *
 * <p><b>委派链失控的两层防御:</b>
 * <ol>
 *   <li><b>深度 1 硬阻断({@link DelegationGuard},主防线)</b>:被委派回合内再委派直接报错,
 *       挡住委派链经<u>空闲</u>实例不断延长(A→B→C→D…每跳合法、每跳阻塞满
 *       {@code jmeter.ai.ipc.agent.timeout.ms} 且深度无界)。</li>
 *   <li><b>接收侧 delegated-busy 兜底</b>:目标实例此刻已有未完成回合占用其单槽
 *       ({@code AgentLoop.hasActiveRun} 为真,注入路由槽存在,不一定是委派回合)时,
 *       新委派快速失败报 "session busy",
 *       避免同一实例并发执行多个回合。这是并发碰撞的保护,与委派链的线性/环状形状无关。</li>
 * </ol>
 *
 * <p><b>委派动销来源标记:</b>载荷带 {@code [delegated-from instanceId=… pid=… script=…]} 来源前缀
 * 让对端会话/GUI 可审计这轮是委派任务及其出处;请求信封同时带 {@code delegated=true},
 * 接收侧据此在本回合内置同一守卫。
 */
public class DelegateToInstanceTool extends AbstractTool {
    public static final String NAME = "delegate_to_instance";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Delegate a task to a PEER JMeter AI instance on this machine and block for its reply. "
                + "The peer runs the task against its own open test plan with its own agent, then returns "
                + "the result. Identify the peer by its currently-open jmx (delegate to whoever holds that "
                + "script) or by instanceId (from list_instances). Do not delegate to yourself. Blocks "
                + "until the peer's agent turn completes or times out (bounded by jmeter.ai.ipc.agent.timeout.ms); "
                + "use it when you need the result in this turn. If the user references a script this instance "
                + "does not have open, a peer instance may hold it — call list_instances first to discover "
                + "peers and their open scripts. Cannot be used from within a delegated turn (depth limit 1).";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "task": {
                            "type": "string",
                            "description": "The task for the peer agent. Be self-contained: the peer sees only this text, not this conversation."
                        },
                        "jmxPath": {
                            "type": "string",
                            "description": "Absolute path of the .jmx the target peer has open. Among peers holding it, picks the most recently started."
                        },
                        "instanceId": {
                            "type": "string",
                            "description": "Specific peer instanceId to target (from list_instances). Takes precedence over jmxPath."
                        }
                    },
                    "required": ["task"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String task = getStringParameter(parameters, "task", "");
        if (task.isBlank()) {
            return ToolResult.error("Parameter 'task' is required");
        }
        // 深度 1 硬阻断:被委派回合内再委派会形成 A↔B ping-pong(两侧互卡满超时),先于寻址拦截
        if (DelegationGuard.isActive()) {
            return ToolResult.error("Delegation depth limit: this task was itself delegated from a peer "
                    + "instance, so it cannot delegate again (prevents cross-instance ping-pong). "
                    + "Complete the task using this instance's own tools.");
        }
        String instanceId = getStringParameter(parameters, "instanceId", null);
        String jmxPath = getStringParameter(parameters, "jmxPath", null);
        boolean hasId = instanceId != null && !instanceId.isBlank();
        boolean hasJmx = jmxPath != null && !jmxPath.isBlank();
        if (!hasId && !hasJmx) {
            return ToolResult.error("Specify either 'instanceId' or 'jmxPath' to identify the target peer.");
        }

        List<InstanceInfo> all = InstanceRegistry.listInstances(ipcDir());
        String self = InstanceContext.instanceId();

        InstanceInfo target;
        String resolutionNote = "";
        if (hasId) {
            target = all.stream().filter(i -> instanceId.equals(i.getInstanceId())).findFirst().orElse(null);
            if (target == null) {
                return ToolResult.error("No live peer with instanceId=" + instanceId
                        + ". Run list_instances to see available peers.");
            }
        } else {
            List<InstanceInfo> matches = new ArrayList<>();
            for (InstanceInfo i : all) {
                String j = i.getJmxPath();
                if (j != null && !j.isEmpty() && sameJmx(j, jmxPath)) {
                    matches.add(i);
                }
            }
            if (matches.isEmpty()) {
                return ToolResult.error("No live peer has jmx '" + jmxPath + "' open. "
                        + "Run list_instances to see peers and their open scripts.");
            }
            // 6.5: 多实例持同 jmx → 最近 startedAt 确定性择一
            matches.sort(Comparator.comparingLong(InstanceInfo::getStartedAt).reversed());
            target = matches.get(0);
            if (matches.size() > 1) {
                resolutionNote = " (note: " + matches.size() + " peers hold this jmx; "
                        + "picked the most recently started: " + target.getInstanceId() + ")";
            }
        }

        if (self.equals(target.getInstanceId())) {
            return ToolResult.error("Target instance is yourself (instanceId=" + self
                    + "). delegate_to_instance is for PEER instances; perform the task directly instead.");
        }

        // 宽限 5s:让目标 /agent 在自身超时后自取消(loop.cancelActiveTask)并回 504,
        // 先于本端 HttpClient 超时,从而拿到结构化错误而非 HttpTimeoutException。
        long timeoutMs = AiConfig.getIpcAgentTimeoutMs() + 5_000L;
        IpcClient client = new IpcClient(hostOf(target), target.getPort(), target.getToken());
        String payload = withProvenance(task, self, selfJmxPath(all, self));
        try {
            IpcResponse resp = client.postAgent(payload, null, timeoutMs, true);
            if (resp.isSuccess()) {
                String content = resp.getContent();
                return ToolResult.success("Delegated to peer " + target.getInstanceId()
                        + " (pid=" + target.getPid() + ")" + resolutionNote + ". Peer agent reply:\n\n"
                        + (content == null || content.isEmpty() ? "(no content)" : content));
            }
            String err = resp.getError() != null ? resp.getError() : resp.getErrorMessage();
            if (resp.isCancelled()) {
                if (IpcResponse.CANCEL_REASON_USER_STOP.equals(resp.getCancelReason())) {
                    String partial = resp.getPartialContent();
                    return ToolResult.error("Peer " + target.getInstanceId()
                            + " cancelled the task: the target instance's user clicked STOP before completion."
                            + (partial == null || partial.isEmpty() ? ""
                                    : "\n\nPartial reply produced before cancellation:\n\n" + partial));
                }
                if (IpcResponse.CANCEL_REASON_TIMEOUT.equals(resp.getCancelReason())) {
                    return ToolResult.error("Peer " + target.getInstanceId()
                            + " timed out and its turn was cancelled there (" + err + ")");
                }
            }
            return ToolResult.error("Peer " + target.getInstanceId() + " failed the task: " + err);
        } catch (Exception e) {
            return ToolResult.error("Failed to delegate to peer " + target.getInstanceId()
                    + ": " + rootMessage(e));
        }
    }

    /** 委派载荷加来源前缀,让对端会话/GUI 可审计这轮是委派任务、来自哪个实例与其打开的脚本。 */
    private static String withProvenance(String task, String selfInstanceId, String selfJmx) {
        String script = (selfJmx == null || selfJmx.isEmpty()) ? "" : " script=" + selfJmx;
        return "[delegated-from instanceId=" + selfInstanceId
                + " pid=" + InstanceRegistry.currentPid() + script + "] " + task;
    }

    /** 本实例端口文件里的 jmxPath(未开脚本为空);无端口文件(IPC 未启动等)返回 null。 */
    private static String selfJmxPath(List<InstanceInfo> all, String selfInstanceId) {
        return all.stream()
                .filter(i -> selfInstanceId.equals(i.getInstanceId()))
                .map(InstanceInfo::getJmxPath)
                .findFirst()
                .orElse(null);
    }

    /** 规范路径比较(容忍大小写/相对-绝对/盘符差异);解析失败退回字面相等。 */
    private static boolean sameJmx(String candidate, String wanted) {
        try {
            return new File(candidate).getCanonicalFile().equals(new File(wanted).getCanonicalFile());
        } catch (IOException e) {
            return candidate.equals(wanted);
        }
    }

    private static String hostOf(InstanceInfo info) {
        String b = info.getBind();
        return (b == null || b.isEmpty()) ? "127.0.0.1" : b;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage();
        return (m != null && !m.isEmpty()) ? m : root.getClass().getSimpleName();
    }

    private static File ipcDir() {
        return InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()));
    }
}
