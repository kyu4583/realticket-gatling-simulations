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
