package simulations.booking.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public interface SubscriptionHandler {

    HttpProtocolBuilder configureProtocol(HttpProtocolBuilder baseProtocol);

    ActionBuilder subscribeAndInitSeatStatus(int targetEvent);

    ActionBuilder reloadSeatStatus();

    ActionBuilder close();

    default int[][] parseSeatStatus(JsonNode seatStatusJson) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            return mapper.treeToValue(seatStatusJson, int[][].class);
        } catch (Exception e) {
            System.err.println("좌석 상태 파싱 오류: " + e.getMessage());
            return new int[0][0];
        }
    }
}
