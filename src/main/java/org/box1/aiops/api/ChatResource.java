package org.box1.aiops.api;

import java.net.URI;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.box1.aiops.chat.ChatService;
import org.box1.aiops.chat.Conversation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import io.smallrye.common.annotation.Blocking;

/**
 * The Assistant's conversations (D-016): ask a question, then poll for the answer. The model may read evidence through
 * read-only tools; nothing here changes any system. Requires aiops-viewer or aiops-analyst.
 */
@Path("/api/v1/ai/conversations")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
@SecurityRequirement(name = "bearer")
public class ChatResource {
    private final ChatService chats;

    @Inject
    public ChatResource(ChatService chats) {
        this.chats = chats;
    }

    public record Created(String conversationId) {
    }

    public record Message(String text) {
    }

    public record Queued(String conversationId, String turnId, String status) {
    }

    @POST
    @Operation(operationId = "createConversation", summary = "Start an Assistant conversation (in memory, owned by you)")
    @APIResponse(responseCode = "201", description = "Created", content = @Content(mediaType = MediaType.APPLICATION_JSON,
        schema = @Schema(implementation = Created.class)))
    @APIResponse(responseCode = "401", description = "No valid token")
    @APIResponse(responseCode = "403", description = "Token lacks aiops-viewer or comes from an unaccepted client")
    public Response create() {
        Conversation c = chats.create();
        return Response.created(URI.create("/api/v1/ai/conversations/" + c.conversationId()))
            .entity(new Created(c.conversationId())).build();
    }

    @POST
    @Path("/{conversationId}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "sendMessage", summary = "Ask a question; poll the conversation for the answer. "
        + "The model may only read evidence; it cannot change anything")
    @APIResponse(responseCode = "202", description = "Queued", content = @Content(mediaType = MediaType.APPLICATION_JSON,
        schema = @Schema(implementation = Queued.class)))
    @APIResponse(responseCode = "400", description = "Empty or too long message, or invalid conversation ID")
    @APIResponse(responseCode = "404", description = "Unknown, expired, lost on restart, or not yours")
    @APIResponse(responseCode = "409", description = "The previous message is still being answered, or the conversation is full")
    @APIResponse(responseCode = "429", description = "Model queue full; see Retry-After")
    public Response send(@PathParam("conversationId") String conversationId, Message message) {
        Conversation.Turn t = chats.send(conversationId, message == null ? null : message.text());
        return Response.accepted(new Queued(conversationId, t.turnId(), t.status().name())).build();
    }

    @GET
    @Path("/{conversationId}")
    @Operation(operationId = "getConversation", summary = "Read a conversation: questions, answers, tool steps and status")
    @APIResponse(responseCode = "200", description = "Conversation", content = @Content(
        mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Conversation.class)))
    @APIResponse(responseCode = "404", description = "Unknown, expired, lost on restart, or not yours")
    public Conversation get(@PathParam("conversationId") String conversationId) {
        return chats.get(conversationId);
    }
}
