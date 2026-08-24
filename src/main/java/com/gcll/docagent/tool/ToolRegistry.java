package com.gcll.docagent.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final Map<String, ToolGateway> toolsByName;

    public ToolRegistry(List<ToolGateway> tools) {
        Map<String, ToolGateway> map = new LinkedHashMap<>();
        for (ToolGateway tool : tools) {
            map.put(tool.toolName(), tool);
        }
        this.toolsByName = Map.copyOf(map);
    }

    public Optional<ToolGateway> find(String toolName) {
        return Optional.ofNullable(toolsByName.get(toolName));
    }

    public Collection<ToolGateway> all() {
        return toolsByName.values();
    }

    public List<ToolDescriptor> descriptors() {
        return toolsByName.values().stream().map(ToolGateway::descriptor).toList();
    }
}
