package org.box1.aiops.mcp;

import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.InvalidArgument;
import org.box1.aiops.NotFound;
import org.box1.aiops.Untrusted;
import org.box1.aiops.analysis.Busy;
import org.box1.aiops.security.AccessDenied;
import org.box1.aiops.security.Guard;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.logging.Log;

/**
 * Wraps every MCP tool call: one audit line (tool, subject, decision, duration, size; never arguments beyond IDs or
 * evidence content), one metric, and a result labelled untrusted. Domain errors become MCP tool errors.
 */
@ApplicationScoped
public class ToolAudit {
    private final Guard guard;
    private final ObjectMapper json;
    private final MeterRegistry registry;

    @Inject
    public ToolAudit(Guard guard, ObjectMapper json, MeterRegistry registry) {
        this.guard = guard;
        this.json = json;
        this.registry = registry;
    }

    private static final java.util.regex.Pattern LOGGABLE =
        java.util.regex.Pattern.compile("^(ord|inc_ord|asset|ana)_[A-Za-z0-9_-]{1,124}$|^[a-z][a-z-]{1,40}$");

    /** Only IDs of a known shape reach the audit log; anything else is logged as "invalid" (review S-5). */
    public static String loggableId(String id) {
        if (id == null || id.isEmpty()) {
            return "-";
        }
        return LOGGABLE.matcher(id).matches() ? id : "invalid";
    }

    public String call(String tool, String id, Supplier<Object> body) {
        long start = System.nanoTime();
        String decision = "allowed";
        int bytes = 0;
        try {
            String text = json.writeValueAsString(Untrusted.wrap(json, json.valueToTree(body.get())));
            bytes = text.length();
            return text;
        } catch (AccessDenied e) {
            decision = e.unauthenticated() ? "unauthenticated" : "forbidden";
            throw new ToolCallException(decision + ": " + e.getMessage());
        } catch (InvalidArgument e) {
            decision = "invalid_argument";
            throw new ToolCallException(e.code() + ": " + e.getMessage());
        } catch (NotFound e) {
            decision = "not_found";
            throw new ToolCallException(e.code() + ": " + e.getMessage());
        } catch (Busy e) {
            decision = "busy";
            throw new ToolCallException("BUSY: " + e.getMessage());
        } catch (ToolCallException e) {
            decision = "error";
            throw e;
        } catch (Exception e) {
            decision = "error";
            throw new ToolCallException("internal error");
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            registry.counter("aiops_mcp_tool_calls_total", "tool", tool, "decision", decision).increment();
            Log.infof("mcp_tool tool=%s id=%s subject=%s decision=%s durationMs=%d resultBytes=%d", tool,
                loggableId(id), guard.subject(), decision, ms, bytes);
        }
    }
}
