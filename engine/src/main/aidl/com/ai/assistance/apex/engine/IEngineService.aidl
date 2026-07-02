package com.ai.assistance.apex.engine;

import com.ai.assistance.apex.engine.model.ExecutionResult;
import com.ai.assistance.apex.engine.model.ToolInfo;
import com.ai.assistance.apex.engine.model.ContainerStatus;
import com.ai.assistance.apex.engine.IToolCallback;
import com.ai.assistance.apex.engine.IContainerCallback;

interface IEngineService {
    ExecutionResult executeCommand(String command);

    void executeCommandAsync(String command, IToolCallback callback);

    ExecutionResult executeTool(String toolName, String args);

    void executeToolAsync(String toolName, String args, IToolCallback callback);

    List<ToolInfo> getAvailableTools();

    ContainerStatus getContainerStatus();

    boolean startContainer();

    boolean stopContainer();

    boolean restartContainer();

    String getContainerOutput();

    void setContainerOutputCallback(IContainerCallback callback);

    boolean requestPermission(String permission);

    boolean checkPermission(String permission);

    String getEngineVersion();

    void shutdown();
}
