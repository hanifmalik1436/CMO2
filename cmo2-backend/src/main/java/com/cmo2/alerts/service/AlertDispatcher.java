package com.cmo2.alerts.service;

import com.cmo2.alerts.channel.AlertChannel;
import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.ChannelType;
import com.cmo2.alerts.dto.AlertDtos.ChannelSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Delivers a fired alert to its configured channels and records what was delivered. */
@Component
public class AlertDispatcher {

    private final Map<ChannelType, AlertChannel> channels = new EnumMap<>(ChannelType.class);
    private final ObjectMapper objectMapper;

    public AlertDispatcher(List<AlertChannel> channelBeans, ObjectMapper objectMapper) {
        for (AlertChannel c : channelBeans) {
            channels.put(c.type(), c);
        }
        this.objectMapper = objectMapper;
    }

    /** Sends to each configured channel; sets {@code alert.dispatchedJson} to the delivered types. */
    public void dispatch(Alert alert, String channelsJson) {
        List<ChannelSpec> specs = parse(channelsJson);
        if (specs.isEmpty()) {
            specs = List.of(new ChannelSpec(ChannelType.IN_APP.name(), null));
        }
        List<String> delivered = new ArrayList<>();
        for (ChannelSpec spec : specs) {
            ChannelType type;
            try {
                type = ChannelType.valueOf(spec.type().trim().toUpperCase());
            } catch (Exception e) {
                continue;
            }
            AlertChannel channel = channels.get(type);
            if (channel != null && channel.send(alert, spec.target())) {
                delivered.add(type.name());
            }
        }
        alert.setDispatchedJson(toJson(delivered));
    }

    private List<ChannelSpec> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<ChannelSpec>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<String> delivered) {
        try {
            return objectMapper.writeValueAsString(delivered);
        } catch (Exception e) {
            return "[]";
        }
    }
}
