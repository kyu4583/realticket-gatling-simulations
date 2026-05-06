package simulations.booking.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SeatStatusMessageParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    private SeatStatusMessageParser() {
    }

    static Optional<SectionSeatStatus> parse(Object inboundMessage) {
        if (inboundMessage == null) {
            return Optional.empty();
        }

        String message = extractMessage(inboundMessage);
        Optional<SectionSeatStatus> parsed = parseText(message);
        if (parsed.isPresent()) {
            return parsed;
        }

        String fallback = inboundMessage.toString();
        if (!fallback.equals(message)) {
            return parseText(fallback);
        }
        return Optional.empty();
    }

    private static String extractMessage(Object inboundMessage) {
        if (inboundMessage instanceof String text) {
            return text;
        }

        try {
            Method messageMethod = inboundMessage.getClass().getMethod("message");
            Object result = messageMethod.invoke(inboundMessage);
            return result == null ? "" : result.toString();
        } catch (ReflectiveOperationException ignored) {
            return inboundMessage.toString();
        }
    }

    private static Optional<SectionSeatStatus> parseText(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        for (String candidate : candidates(raw)) {
            Optional<SectionSeatStatus> parsed = parseJson(candidate);
            if (parsed.isPresent()) {
                return parsed;
            }
        }

        return Optional.empty();
    }

    private static List<String> candidates(String raw) {
        List<String> candidates = new ArrayList<>();
        candidates.add(raw.trim());

        String dataPayload = extractSseDataPayload(raw);
        if (!dataPayload.isBlank()) {
            candidates.add(dataPayload);
        }

        int firstBrace = raw.indexOf('{');
        int lastBrace = raw.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            candidates.add(raw.substring(firstBrace, lastBrace + 1));
        }

        return candidates;
    }

    private static String extractSseDataPayload(String raw) {
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                data.append(trimmed.substring("data:".length()).trim());
            }
        }
        return data.toString();
    }

    private static Optional<SectionSeatStatus> parseJson(String candidate) {
        try {
            JsonNode payload = unwrapData(mapper.readTree(candidate));
            int sectionIndex = payload.path("sectionIndex").asInt(-1);
            JsonNode seatStatusNode = payload.get("seatStatus");
            if (sectionIndex < 0 || seatStatusNode == null || !seatStatusNode.isArray()) {
                return Optional.empty();
            }

            int[] seatStatus = mapper.treeToValue(seatStatusNode, int[].class);
            return Optional.of(new SectionSeatStatus(sectionIndex, seatStatus));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static JsonNode unwrapData(JsonNode root) throws Exception {
        JsonNode payload = root;
        while (payload.has("data")) {
            JsonNode data = payload.get("data");
            payload = data.isTextual() ? mapper.readTree(data.asText()) : data;
        }
        return payload;
    }
}
