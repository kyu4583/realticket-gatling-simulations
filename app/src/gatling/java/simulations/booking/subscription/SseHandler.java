package simulations.booking.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatling.http.action.sse.SseInboundMessage;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.sse;
import static simulations.config.Config.Url.ROOT_URL_HTTP;

public class SseHandler implements SubscriptionHandler {
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public HttpProtocolBuilder configureProtocol(HttpProtocolBuilder baseProtocol) {
        return baseProtocol.sseUnmatchedInboundMessageBufferSize(1);
    }
    
    @Override
    public ActionBuilder subscribeAndInitSeatStatus(int targetEvent) {
        return sse("SSE 연결")
                .get(ROOT_URL_HTTP + "/booking/seat/" + targetEvent)
                .await(32).on(
                        sse.checkMessage("메시지 형태 체크")
                                .check(
                                        jsonPath("$.data").exists(),
                                        jsonPath("$.data")
                                                .transform(dataStr -> {
                                                    try {
                                                        return mapper.readTree(dataStr).get("seatStatus");
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
        return sse.processUnmatchedMessages((messages, session) -> {
            SseInboundMessage latest = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (latest == null) {
                return session;
            }
            
            String data = latest.toString();
            String jsonStr = data.substring(data.indexOf(",") + 1, data.lastIndexOf("}") + 1);
            
            try {
                JsonNode rootNode = mapper.readTree(jsonStr);
                String dataStr = rootNode.get("data").asText();
                JsonNode seatStatusJson = mapper.readTree(dataStr).get("seatStatus");
                int[][] seatStatus = mapper.treeToValue(seatStatusJson, int[][].class);
                return session.set("seatStatus", seatStatus);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    @Override
    public ActionBuilder close() {
        return sse("SSE 연결 종료").close();
    }
}
