package org.box1.aiops.mcp;

import io.quarkiverse.mcp.server.Tool;

/** Q-2 compatibility probe only; replaced by the real tool catalog (D-004). */
public class CompatProbeTool {
    @Tool(description = "Returns the service name; compatibility probe")
    String serviceName() {
        return "agentic-operations";
    }
}
