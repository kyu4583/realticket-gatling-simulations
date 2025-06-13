package simulations.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gatling.http.action.ws.WsInboundMessage;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.ArrayList;
import java.util.List;

import static simulations.config.Config.*;
import static simulations.config.Config.Url.ROOT_URL_WS;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.ws;

public class WsBookingSimulation extends BasicBookingSimulation {

    protected HttpProtocolBuilder httpProtocol = super.httpProtocol
            .wsUnmatchedInboundMessageBufferSize(1);

    protected ScenarioBuilder scn =
            createScenario("이벤트=1 좌석 예매 시나리오(웹소켓)")
            .exec(ws("웹소켓 종료").close());

    @Override
    protected ActionBuilder subscribeSeatsAndInitAvailableSeats() {
        return ws("웹소켓 연결").connect(ROOT_URL_WS + "/benchmark/seat?eventId=" + TARGET_EVENT).await(32).on(
                ws.checkTextMessage("메시지 형태 체크")
                        .check(
                                jsonPath("$.data.seatStatus").exists(),
                                jsonPath("$.data.seatStatus")
                                        .transform(jsonStr -> parseAvailableSeatsFromJsonStr(jsonStr))
                                        .saveAs("availableSeats")
                        )
        );
    }

    @Override
    protected ActionBuilder reloadAvailableSeats() {
        return ws.processUnmatchedMessages((messages, session) -> {
            // 가장 최신 메시지 선택
            WsInboundMessage latest = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (latest == null) {
                return session;
            }
            String data = latest.toString();
            String jsonStr = data.substring(data.indexOf(",") + 1, data.lastIndexOf("}") + 1);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            try {
                String seatStatusStr = mapper.readTree(jsonStr).get("data").get("seatStatus").toString();

                if (ENABLE_BOOLEAN_SEATS_FORMAT) {
                    boolean[][] seatStatus = mapper.readValue(seatStatusStr, boolean[][].class);
                    List<int[]> availableSeats = new ArrayList<>();
                    for (int sectionIdx = 0; sectionIdx < seatStatus.length; sectionIdx++) {
                        for (int seatIdx = 0; seatIdx < seatStatus[sectionIdx].length; seatIdx++) {
                            if (seatStatus[sectionIdx][seatIdx]) {
                                availableSeats.add(new int[]{sectionIdx, seatIdx});
                            }
                        }
                    }
                    return session.set("availableSeats", availableSeats);
                } else {
                    int[][] seatStatus = mapper.readValue(seatStatusStr, int[][].class);
                    List<int[]> availableSeats = new ArrayList<>();
                    for (int sectionIdx = 0; sectionIdx < seatStatus.length; sectionIdx++) {
                        for (int seatIdx = 0; seatIdx < seatStatus[sectionIdx].length; seatIdx++) {
                            if (seatStatus[sectionIdx][seatIdx] == 1) {
                                availableSeats.add(new int[]{sectionIdx, seatIdx});
                            }
                        }
                    }
                    return session.set("availableSeats", availableSeats);
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }

    {
        // 시뮬레이션 설정
        setUp(scn.injectOpen(atOnceUsers(3))).protocols(httpProtocol);

        /*
         시뮬레이션 설정 예제

        // 1. atOnceUsers: 지정된 수의 사용자를 동시에 한 번에 주입
        setUp(scn.injectOpen(atOnceUsers(10))).protocols(httpProtocol); ; // 10명의 사용자를 동시에 시작

        // 2. rampUsers: 지정된 기간 동안 사용자 수를 점진적으로 증가
        setUp(scn.injectOpen(rampUsers(100).during(Duration.ofSeconds(10)))).protocols(httpProtocol); ; // 10초 동안 100명의 사용자 점진적 증가

        // 3. constantUsersPerSec: 초당 일정한 사용자 수를 지정된 기간 동안 주입
        setUp(scn.injectOpen(constantUsersPerSec(10).during(Duration.ofMinutes(5)))).protocols(httpProtocol); ; // 5분 동안 초당 10명의 사용자

        // 4. rampUsersPerSec: 초당 사용자 수를 시작 값에서 목표 값까지 점진적으로 증가
        setUp(scn.injectOpen(rampUsersPerSec(10).to(20).during(Duration.ofMinutes(3)))).protocols(httpProtocol); ; // 3분 동안 초당 10명에서 20명으로 증가

        // 5. nothingFor: 지정된 기간 동안 사용자 주입을 중지
        setUp(scn.injectOpen(
                nothingFor(Duration.ofSeconds(10)), // 10초 동안 대기
                atOnceUsers(10) // 그 후 10명의 사용자 주입
        )).protocols(httpProtocol); ;

        // 7. incrementUsersPerSec: 초당 사용자 수를 단계별로 증가
        setUp(scn.injectOpen(
                incrementUsersPerSec(5) // 초당 5명씩 증가
                        .times(5) // 5번 반복 (최종 초당 25명)
                        .eachLevelLasting(Duration.ofSeconds(10)) // 각 레벨 10초 유지
                        .separatedByRampsLasting(Duration.ofSeconds(5)) // 각 레벨 사이 5초 동안 점진적 증가
                        .startingFrom(5) // 초당 5명부터 시작
        )).protocols(httpProtocol); ;

        // 8. stressPeakUsers: 갑작스러운 트래픽 스파이크 시뮬레이션
        setUp(scn.injectOpen(
                stressPeakUsers(1000).during(Duration.ofSeconds(20)) // 20초 동안 1000명의 사용자를 급격히 주입
        )).protocols(httpProtocol); ;

        // 조합 예제
        setUp(scn.injectOpen(
                nothingFor(Duration.ofSeconds(5)),
                atOnceUsers(10),
                rampUsers(50).during(Duration.ofMinutes(2)),
                constantUsersPerSec(20).during(Duration.ofMinutes(5)),
                rampUsersPerSec(20).to(40).during(Duration.ofMinutes(3)),
                nothingFor(Duration.ofSeconds(30)),
                stressPeakUsers(100).during(Duration.ofSeconds(10))
        )).protocols(httpProtocol); ;
         */

    }
}