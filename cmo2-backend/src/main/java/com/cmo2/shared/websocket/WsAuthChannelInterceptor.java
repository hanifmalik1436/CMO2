package com.cmo2.shared.websocket;

import com.cmo2.shared.security.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Authenticates the STOMP CONNECT frame from its bearer token and enforces tenant
 * isolation on the socket: a client may only SUBSCRIBE to its OWN tenant topic
 * ({@code /topic/tenant.{tenantId}.loop}). This extends NON-NEGOTIABLE RULE #1
 * (tenant isolation) to the realtime channel.
 */
@Component
public class WsAuthChannelInterceptor implements ChannelInterceptor {

    private static final String TENANT_ATTR = "tenantId";

    private final JwtService jwtService;

    public WsAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String bearer = firstNativeHeader(accessor, "Authorization");
            if (bearer == null || !bearer.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing bearer token on STOMP CONNECT");
            }
            JwtService.ParsedToken token = jwtService.parse(bearer.substring(7));
            accessor.setUser(new StompPrincipal(token.userId(), token.tenantId()));
            Map<String, Object> attrs = accessor.getSessionAttributes();
            if (attrs != null) {
                attrs.put(TENANT_ATTR, token.tenantId());
            }
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            Long tenantId = tenantIdOf(accessor);
            String destination = accessor.getDestination();
            String allowed = "/topic/tenant." + tenantId + ".loop";
            if (tenantId == null || destination == null || !destination.equals(allowed)) {
                throw new IllegalArgumentException("Subscription to '" + destination + "' is not permitted");
            }
        }

        return message;
    }

    private Long tenantIdOf(StompHeaderAccessor accessor) {
        Object fromUser = accessor.getUser() instanceof StompPrincipal p ? p.tenantId() : null;
        if (fromUser != null) {
            return (Long) fromUser;
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        return attrs == null ? null : (Long) attrs.get(TENANT_ATTR);
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
