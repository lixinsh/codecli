package com.cugb.plan;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 执行计划类，用于管理多个任务的执行顺序
 * 通过拓扑排序算法将任务的有向无环图（DAG）转换为线性执行顺序
 */
public class ExecutionPlan {
    private final String id;                        // 执行计划唯一标识
    private List<String> executionOrder;            // 执行顺序（任务ID列表）
    private PlanStatus status;                      // 执行计划状态
    private final Map<String, Task> tasks;          // 所有任务对象（任务ID -> Task对象）
    private final LocalDateTime createdAt;          // 创建时间
    private LocalDateTime startedAt;                // 开始执行时间
    private LocalDateTime completedAt;              // 完成时间
    private String errorMessage;                    // 错误信息

    /**
     * 执行计划状态枚举
     */
    public enum PlanStatus {
        CREATED,        // 已创建
        READY,          // 已就绪（拓扑排序完成）
        RUNNING,        // 任务执行中
        COMPLETED,      // 所有任务都已完成
        FAILED,         // 有任务失败
        CANCELLED       // 已取消
    }

    /**
     * 构造函数
     */
    public ExecutionPlan() {
        this.id = UUID.randomUUID().toString();
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
        this.tasks = new LinkedHashMap<>();
        this.createdAt = LocalDateTime.now();
        this.startedAt = null;
        this.completedAt = null;
        this.errorMessage = null;
    }

    // Getter 方法
    public String getId() {
        return id;
    }

    public List<String> getExecutionOrder() {
        return executionOrder;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // Setter 方法
    public void setStatus(PlanStatus status) {
        this.status = status;
        if (status == PlanStatus.RUNNING && this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
        if (status == PlanStatus.COMPLETED || status == PlanStatus.FAILED || status == PlanStatus.CANCELLED) {
            this.completedAt = LocalDateTime.now();
        }
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * 添加任务到执行计划
     *
     * @param task 任务对象
     */
    public void addTask(Task task) {
        if (task != null && !tasks.containsKey(task.getId())) {
            tasks.put(task.getId(), task);
        }
    }

    // /**
    //  * 批量添加任务
    //  *
    //  * @param taskList 任务列表
    //  */
    // public void addTasks(List<Task> taskList) {
    //     if (taskList != null) {
    //         for (Task task : taskList) {
    //             addTask(task);
    //         }
    //     }
    // }

    /**
     * 根据任务ID获取任务
     *
     * @param taskId 任务ID
     * @return 任务对象，不存在则返回null
     */
    public Task getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 获取所有任务列表
     *
     * @return 任务列表
     */
    public List<Task> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 执行拓扑排序，将任务的DAG转换为线性执行顺序
     * 使用Kahn算法实现拓扑排序
     *
     * @return 是否成功完成拓扑排序
     */
    public boolean topologicalSort() {
        if (tasks.isEmpty()) {
            this.executionOrder = new ArrayList<>();
            this.status = PlanStatus.READY;
            return true;
        }

        // 1. 计算每个任务的入度（依赖数量）
        Map<String, Integer> inDegree = new HashMap<>();
        for (String taskId : tasks.keySet()) {
            inDegree.putIfAbsent(taskId, 0);
        }

        // 统计每个任务的入度
        for (Task task : tasks.values()) {
            for (String dependency : task.getDependencies()) {
                // 只统计存在于当前执行计划中的依赖
                if (tasks.containsKey(dependency)) {
                    inDegree.merge(task.getId(), 1, Integer::sum);
                }
            }
        }

        // 2. 将所有入度为0的任务加入队列
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // 3. 执行拓扑排序
        List<String> sortedOrder = new ArrayList<>();
        while (!queue.isEmpty()) {
            String currentTaskId = queue.poll();
            sortedOrder.add(currentTaskId);

            // 查找所有依赖于当前任务的任务
            for (Task task : tasks.values()) {
                if (task.getDependencies().contains(currentTaskId)) {
                    String dependentId = task.getId();
                    // 减少入度
                    inDegree.merge(dependentId, -1, Integer::sum);
                    // 如果入度变为0，加入队列
                    if (inDegree.get(dependentId) == 0) {
                        queue.offer(dependentId);
                    }
                }
            }
        }

        // 4. 检查是否存在环
        if (sortedOrder.size() != tasks.size()) {
            this.errorMessage = "检测到任务依赖中存在循环依赖，无法生成执行顺序";
            this.status = PlanStatus.FAILED;
            return false;
        }

        // 5. 设置执行顺序
        this.executionOrder = sortedOrder;
        this.status = PlanStatus.READY;
        this.errorMessage = null;
        return true;
    }

    // /**
    //  * 获取下一个可执行的任务ID列表（所有依赖已完成的任务）
    //  *
    //  * @param completedTaskIds 已完成的任务ID列表
    //  * @return 可执行的任务ID列表
    //  */
    // public List<String> getNextExecutableTasks(List<String> completedTaskIds) {
    //     List<String> executableTasks = new ArrayList<>();
    //     
    //     for (String taskId : executionOrder) {
    //         // 跳过已完成的任务
    //         if (completedTaskIds.contains(taskId)) {
    //             continue;
    //         }
    //         
    //         Task task = tasks.get(taskId);
    //         if (task != null && task.canExecute(completedTaskIds)) {
    //             executableTasks.add(taskId);
    //         }
    //     }
    //     
    //     return executableTasks;
    // }

    // /**
    //  * 检查执行计划是否全部完成
    //  *
    //  * @param completedTaskIds 已完成的任务ID列表
    //  * @return 是否全部完成
    //  */
    // public boolean isAllCompleted(List<String> completedTaskIds) {
    //     return completedTaskIds.containsAll(executionOrder);
    // }

    // /**
    //  * 重置执行计划
    //  */
    // public void reset() {
    //     this.executionOrder.clear();
    //     this.status = PlanStatus.CREATED;
    //     this.startedAt = null;
    //     this.completedAt = null;
    //     this.errorMessage = null;
    //     
    //     // 重置所有任务状态
    //     for (Task task : tasks.values()) {
    //         task.setStatus(Task.TaskStatus.PENDING);
    //         task.setResult(null);
    //         task.setErrorMessage(null);
    //     }
    // }

    // /**
    //  * 验证执行计划的完整性
    //  *
    //  * @return 是否有效
    //  */
    // public boolean validate() {
    //     // 检查是否有任务
    //     if (tasks.isEmpty()) {
    //         this.errorMessage = "执行计划中没有任务";
    //         return false;
    //     }
    //
    //     // 检查所有依赖是否都存在于执行计划中
    //     for (Task task : tasks.values()) {
    //         for (String dependency : task.getDependencies()) {
    //             if (!tasks.containsKey(dependency)) {
    //                 this.errorMessage = "任务 " + task.getId() + " 依赖的任务 " + dependency + " 不存在于执行计划中";
    //                 return false;
    //             }
    //         }
    //     }
    //
    //     return true;
    // }

    @Override
    public String toString() {
        return "ExecutionPlan{" +
                "id='" + id + '\'' +
                ", executionOrder=" + executionOrder +
                ", status=" + status +
                ", taskCount=" + tasks.size() +
                ", createdAt=" + createdAt +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }

    // /**
    //  * 打印执行计划的详细信息
    //  */
    // public void printDetails() {
    //     System.out.println("=== 执行计划详情 ===");
    //     System.out.println("计划ID: " + id);
    //     System.out.println("状态: " + status);
    //     System.out.println("任务数量: " + tasks.size());
    //     System.out.println("执行顺序: " + executionOrder);
    //     System.out.println("创建时间: " + createdAt);
    //     System.out.println("开始时间: " + (startedAt != null ? startedAt : "未开始"));
    //     System.out.println("完成时间: " + (completedAt != null ? completedAt : "未完成"));
    //     if (errorMessage != null) {
    //         System.out.println("错误信息: " + errorMessage);
    //     }
    //     System.out.println("\n任务列表:");
    //     for (int i = 0; i < executionOrder.size(); i++) {
    //         String taskId = executionOrder.get(i);
    //         Task task = tasks.get(taskId);
    //         if (task != null) {
    //             System.out.println("  " + (i + 1) + ". [" + task.getType() + "] " + task.getDescription());
    //             System.out.println("     ID: " + task.getId());
    //             System.out.println("     状态: " + task.getStatus());
    //             System.out.println("     依赖: " + task.getDependencies());
    //             System.out.println("     被依赖: " + task.getDependents());
    //         }
    //     }
    //     System.out.println("==================");
    // }

    /**
     * 生成执行计划的文本摘要
     * 用于重新规划时向大模型提供当前计划的上下文信息
     *
     * @return 计划摘要文本
     */
    public String toSummaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("执行计划状态：").append(status).append("\n");

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> running = new ArrayList<>();
        List<String> cancelled = new ArrayList<>();
        List<String> pending = new ArrayList<>();

        for (String taskId : executionOrder) {
            Task task = tasks.get(taskId);
            if (task == null) continue;
            switch (task.getStatus()) {
                case COMPLETED -> completed.add(taskId);
                case FAILED    -> failed.add(taskId);
                case RUNNING   -> running.add(taskId);
                case CANCELLED -> cancelled.add(taskId);
                default        -> pending.add(taskId);
            }
        }

        // 已完成的任务
        if (!completed.isEmpty()) {
            sb.append("\n已完成的任务：\n");
            for (int i = 0; i < executionOrder.size(); i++) {
                String taskId = executionOrder.get(i);
                if (!completed.contains(taskId)) continue;
                Task task = tasks.get(taskId);
                sb.append("  ").append(i).append(". [").append(task.getType())
                  .append("] ").append(task.getDescription());
                if (task.getResult() != null) {
                    sb.append(" (结果: ").append(task.getResult()).append(")");
                }
                sb.append("\n");
            }
        }

        // 失败的任务
        if (!failed.isEmpty()) {
            sb.append("\n失败的任务：\n");
            for (int i = 0; i < executionOrder.size(); i++) {
                String taskId = executionOrder.get(i);
                if (!failed.contains(taskId)) continue;
                Task task = tasks.get(taskId);
                sb.append("  ").append(i).append(". [").append(task.getType())
                  .append("] ").append(task.getDescription())
                  .append(" 错误: ").append(task.getErrorMessage() != null ? task.getErrorMessage() : "未知错误")
                  .append("\n");
            }
        }

        // 执行中的任务
        if (!running.isEmpty()) {
            sb.append("\n执行中的任务：\n");
            for (int i = 0; i < executionOrder.size(); i++) {
                String taskId = executionOrder.get(i);
                if (!running.contains(taskId)) continue;
                Task task = tasks.get(taskId);
                sb.append("  ").append(i).append(". [").append(task.getType())
                  .append("] ").append(task.getDescription()).append("\n");
            }
        }

        // 已取消的任务
        if (!cancelled.isEmpty()) {
            sb.append("\n已取消的任务：\n");
            for (int i = 0; i < executionOrder.size(); i++) {
                String taskId = executionOrder.get(i);
                if (!cancelled.contains(taskId)) continue;
                Task task = tasks.get(taskId);
                sb.append("  ").append(i).append(". [").append(task.getType())
                  .append("] ").append(task.getDescription());
                if (task.getErrorMessage() != null) {
                    sb.append(" (因前置失败: ").append(task.getErrorMessage()).append(")");
                }
                sb.append("\n");
            }
        }

        // 待执行的任务
        if (!pending.isEmpty()) {
            sb.append("\n待执行的任务：\n");
            for (int i = 0; i < executionOrder.size(); i++) {
                String taskId = executionOrder.get(i);
                if (!pending.contains(taskId)) continue;
                Task task = tasks.get(taskId);
                sb.append("  ").append(i).append(". [").append(task.getType())
                  .append("] ").append(task.getDescription());
                if (!task.getDependencies().isEmpty()) {
                    sb.append(" 依赖序号: ");
                    List<String> depIndices = new ArrayList<>();
                    for (String depId : task.getDependencies()) {
                        int idx = executionOrder.indexOf(depId);
                        if (idx != -1) depIndices.add(String.valueOf(idx));
                    }
                    sb.append(String.join(", ", depIndices));
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
