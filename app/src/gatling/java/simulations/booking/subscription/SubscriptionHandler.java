package simulations.booking.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public interface SubscriptionHandler {

    HttpProtocolBuilder configureProtocol(HttpProtocolBuilder baseProtocol);

    ActionBuilder subscribe(int targetEvent);

    ActionBuilder reloadSeatStatus();

    ActionBuilder close();

    // 특정 section을 지정하여 SSE 연결 (기본: section 무시하고 subscribe 위임)
    default ActionBuilder subscribeWithSection(int targetEvent, int section) {
        return subscribe(targetEvent);
    }

    // 세션 속성 "sseTargetSection"을 읽어 section 지정 SSE 재연결 (기본: section 무시하고 subscribe 위임)
    default ActionBuilder reconnectToSection(int targetEvent) {
        return subscribe(targetEvent);
    }

    default int[] parseSeatStatus(JsonNode seatStatusJson) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.treeToValue(seatStatusJson, int[].class);
        } catch (Exception e) {
            System.err.println("seatStatus parse error: " + e.getMessage());
            return new int[0];
        }
    }
}
