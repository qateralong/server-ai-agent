package com.bebebe.agent.tools;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    Map<String, Object> parameters();

    ToolResult execute(Map<String, Object> arguments, ToolContext context);
}
