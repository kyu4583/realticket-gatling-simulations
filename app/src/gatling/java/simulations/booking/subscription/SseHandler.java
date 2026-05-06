package simulations.booking.subscription;

import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.http.HttpDsl.sse;
import static simulations.config.Config.Url.ROOT_URL_HTTP;

public class SseHandler implements SubscriptionHandler {

    @Override
    public HttpProtocolBuilder configureProtocol(HttpProtocolBuilder baseProtocol) {
        return baseProtocol.sseUnmatchedInboundMessageBufferSize(1);
    }

    @Override
    public ActionBuilder subscribe(int targetEvent) {
        return sse("SSE 연결")
                .get(ROOT_URL_HTTP + "/booking/seat/" + targetEvent);
    }

    @Override
    public ActionBuilder reloadSeatStatus() {
        return sse.processUnmatchedMessages((messages, session) -> {
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
        return sse("SSE 연결 종료").close();
    }
}
