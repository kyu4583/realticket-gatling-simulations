package simulations.booking.subscription;

import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.http.HttpDsl.ws;
import static simulations.config.Config.Url.ROOT_URL_WS;

public class WsHandler implements SubscriptionHandler {

    @Override
    public HttpProtocolBuilder configureProtocol(HttpProtocolBuilder baseProtocol) {
        return baseProtocol.wsUnmatchedInboundMessageBufferSize(1);
    }

    @Override
    public ActionBuilder subscribe(int targetEvent) {
        return ws("웹소켓 연결")
                .connect(ROOT_URL_WS + "/benchmark/seat?eventId=" + targetEvent);
    }

    @Override
    public ActionBuilder reloadSeatStatus() {
        return ws.processUnmatchedMessages((messages, session) -> {
            Integer currentSection = session.get("currentSection");

            for (int i = messages.size() - 1; i >= 0; i--) {
                var parsed = SeatStatusMessageParser.parse(messages.get(i));
                if (parsed.isEmpty()) {
                    continue;
                }

                SectionSeatStatus sectionSeatStatus = parsed.get();
                if (currentSection != null && sectionSeatStatus.sectionIndex() != currentSection) {
                    continue;
                }

                return session
                        .set("currentSection", sectionSeatStatus.sectionIndex())
                        .set("seatStatus", sectionSeatStatus.seatStatus());
            }

            return session;
        });
    }

    @Override
    public ActionBuilder close() {
        return ws("웹소켓 종료").close();
    }
}
