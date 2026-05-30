package com.cugb.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolRegistry() {
        registerFileTools();
        registerWriteFileTool();
        registerShellExecTool();
    }

    /**
     * 创建符合 OpenAI 规范的 parameters JsonNode
     * @param params 参数定义的可变长数组
     */
    public JsonNode createParameters(Param... params) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        
        ObjectNode propertiesNode = root.putObject("properties");
        ArrayNode requiredNode = root.putArray("required");

        for (Param param : params) {
            // 构建单个参数的属性对象
            ObjectNode propDetail = propertiesNode.putObject(param.name());
            propDetail.put("type", param.type());
            propDetail.put("description", param.description());

            // 如果标记为必填，加入 required 数组
            if (param.required()) {
                requiredNode.add(param.name());
            }
        }

        return root;
    }

    private void registerFileTools() {
        // 1. 注册 read_file 工具
        Param filePathParam = new Param(
                "file_path",
                "string",
                "要读取的文件的绝对路径或相对路径",
                true
        );
        
        JsonNode readParams = createParameters(filePathParam);
        ToolExecutor readExecutor = args -> {
            String path = args.get("file_path");
            if (path == null || path.isEmpty()) return "错误: 未提供文件路径";
            try {
                return Files.readString(Paths.get(path));
            } catch (IOException e) {
                return "读取文件失败: " + e.getMessage();
            }
        };
        tools.put("read_file", new Tool("read_file", "读取指定路径的文件内容", readParams, readExecutor));
    }

    private void registerWriteFileTool() {
        // 2. 注册 write_file 工具
        Param writePathParam = new Param("file_path", "string", "要写入的文件路径", true);
        Param contentParam = new Param("content", "string", "要写入的文件内容", true);
        JsonNode writeParams = createParameters(writePathParam, contentParam);
        
        ToolExecutor writeExecutor = args -> {
            String path = args.get("file_path");
            String content = args.get("content");
            if (path == null || content == null) return "错误: 缺少必要参数";
            try {
                Files.writeString(Paths.get(path), content);
                return "文件已成功写入: " + path;
            } catch (IOException e) {
                return "写入文件失败: " + e.getMessage();
            }
        };
        tools.put("write_file", new Tool("write_file", "创建新文件或覆盖现有文件的内容", writeParams, writeExecutor));
    }

    private void registerShellExecTool() {
        // 3. 注册 shell_exec 工具
        Param commandParam = new Param("command", "string", "要在系统终端中执行的 Shell 命令", true);
        JsonNode shellParams = createParameters(commandParam);
        
        ToolExecutor shellExecutor = args -> {
            String cmd = args.get("command");
            if (cmd == null || cmd.isEmpty()) return "错误: 未提供命令";
            try {
                ProcessBuilder pb = new ProcessBuilder();
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    pb.command("cmd.exe", "/c", cmd);
                } else {
                    pb.command("sh", "-c", cmd);
                }
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String result = new String(p.getInputStream().readAllBytes());
                int exitCode = p.waitFor();
                return "命令执行完毕 (退出码: " + exitCode + ")\n输出:\n" + result;
            } catch (Exception e) {
                return "执行命令失败: " + e.getMessage();
            }
        };
        tools.put("shell_exec", new Tool("shell_exec", "在本地系统执行 Shell 命令并返回输出结果", shellParams, shellExecutor));
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public Map<String, Tool> getAllTools() {
        return new HashMap<>(tools);
    }


}

