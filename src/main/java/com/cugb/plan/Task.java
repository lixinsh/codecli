package com.cugb.plan;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务模型类，用于实现 plan-and-execute 模式
 * 将复杂任务分解为多个子任务，实现规划和执行分离
 */
public class Task {
    private final String id;                    // 任务唯一标识
    private final TaskType type;                // 任务类型
    private String description;                 // 任务描述
    private TaskStatus status;                  // 任务状态
    private final List<String> dependencies;    // 依赖任务列表（本任务依赖的其他任务）
    private final List<String> dependents;      // 被依赖任务列表（依赖本任务的其他任务）
    private String errorMessage;                // 错误信息
    private final LocalDateTime createdAt;      // 创建时间
    private LocalDateTime completedAt;          // 结束时间
    private Object result;                      // 任务结果

    public void setStatus(TaskStatus taskStatus) {
        this.status = taskStatus;
    }

    /**
     * 任务类型枚举
     */
    public enum TaskType {
        READ,       // 读取文件，获取信息
        WRITE,      // 写入文件，输出结果
        EXECUTE,    // 执行命令，编译执行等
        ANALYZE,    // 分析结果，中间决策
        VERIFY      // 验证结果，检查正确性
    }

    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING,      // 等待执行
        RUNNING,      // 执行中
        COMPLETED,    // 已完成
        FAILED,       // 失败
        CANCELLED     // 已取消
    }

    /**
     * 构造函数 - 创建新任务
     *
     * @param type         任务类型
     * @param description  任务描述
     * @param dependencies 依赖的任务ID列表
     */
    public Task(TaskType type, String description, List<String> dependencies) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.description = description;
        this.status = TaskStatus.PENDING;
        this.dependencies = dependencies != null ? new ArrayList<>(dependencies) : new ArrayList<>();
        this.dependents = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.errorMessage = null;
        this.completedAt = null;
        this.result = null;
    }

    /**
     * 构造函数 - 创建无依赖的任务
     *
     * @param type        任务类型
     * @param description 任务描述
     */
    public Task(TaskType type, String description) {
        this(type, description, new ArrayList<>());
    }

    // Getter 方法
    public String getId() {
        return id;
    }

    public TaskType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    /**
     * 添加依赖任务
     *
     * @param taskId 依赖的任务ID
     */
    public void addDependency(String taskId) {
        if (taskId != null && !dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    /**
     * 添加被依赖任务
     *
     * @param taskId 被依赖的任务ID
     */
    public void addDependent(String taskId) {
        if (taskId != null && !dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Object getResult() {
        return result;
    }

    /**
     * 检查任务是否可以执行（所有依赖任务都已完成）
     *
     * @param completedTaskIds 已完成任务的ID集合
     * @return 如果所有依赖任务都已完成则返回true
     */
    public boolean canExecute(List<String> completedTaskIds) {
        if (dependencies.isEmpty()) {
            return true;
        }
        return completedTaskIds.containsAll(dependencies);
    }

    /**
     * 标记任务为成功完成
     *
     * @param result 任务执行结果
     */
    public void complete(Object result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 标记任务为失败
     *
     * @param errorMessage 错误信息
     */
    public void fail(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 标记任务为已取消
     */
    public void cancel() {
        this.status = TaskStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Task{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", description='" + description + '\'' +
                ", status=" + status +
                ", dependencies=" + dependencies +
                ", dependents=" + dependents +
                ", errorMessage='" + errorMessage + '\'' +
                ", createdAt=" + createdAt +
                ", completedAt=" + completedAt +
                ", result=" + result +
                '}';
    }
}
