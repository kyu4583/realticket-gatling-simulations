package simulations.booking.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatling.http.action.ws.WsInboundMessage;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.ws;
import static simulations.config.Config.Url.ROOT_URL_WS;

public class WsHandler implements SubscriptionHandler {
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public HttpProtocolBuilder configureProtocol(HttpProtocolBuilder baseProtocol) {
        return baseProtocol.wsUnmatchedInboundMessageBufferSize(1);
    }
    
    @Override
    public ActionBuilder subscribeAndInitSeatStatus(int targetEvent) {
        return ws("웹소켓 연결")
                .connect(ROOT_URL_WS + "/benchmark/seat?eventId=" + targetEvent)
                .await(100).on(
                        ws.checkTextMessage("메시지 형태 체크")
                                .check(
                                        jsonPath("$.data.seatStatus").exists(),
                                        jsonPath("$.data.seatStatus")
                                                .transform(seatsStr -> {
                                                    try {
                                                        return mapper.readTree(seatsStr);
                                                    } catch (Exception e) {
                                                        throw new RuntimeException("데이터 파싱 오류: " + e.getMessage());
                                                    }
                                                })
                                                .transform(this::parseSeatStatus)
                                                .saveAs("seatStatus")
                                )
                );
    }
    
    @Override
    public ActionBuilder reloadSeatStatus() {
        return ws.processUnmatchedMessages((messages, session) -> {
            WsInboundMessage latest = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (latest == null) {
                return session;
            }
            
            String data = latest.toString();
            String jsonStr = data.substring(data.indexOf(",") + 1, data.lastIndexOf("}") + 1);
            
            try {
                JsonNode seatStatusJson = mapper.readTree(jsonStr).get("data").get("seatStatus");
                int[][] seatStatus = mapper.treeToValue(seatStatusJson, int[][].class);
                return session.set("seatStatus", seatStatus);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    @Override
    public ActionBuilder close() {
        return ws("웹소켓 종료").close();
    }
}
