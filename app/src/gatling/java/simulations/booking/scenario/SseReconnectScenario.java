package simulations.booking.scenario;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import simulations.booking.core.BookingActions;
import simulations.booking.core.PlanLoader;
import simulations.booking.core.PlanLoader.PlannedRequest;
import simulations.booking.core.PlanLoader.RequestType;
import simulations.booking.core.SessionStore;
import simulations.booking.subscription.SubscriptionHandler;
import simulations.util.AsyncLogger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static simulations.config.Config.*;

public class SseReconnectScenario implements ScenarioExecutor {

    private static final AtomicInteger requestReadyCount = new AtomicInteger(0);
    private static final AtomicInteger requestCompletedCount = new AtomicInteger(0);
    private static final AtomicLong simulationStartTime = new AtomicLong(0);

    @Override
    public PopulationBuilder[] build(SubscriptionHandler subscription) {
        int numUsers = PlanLoader.getNumUsers();

        return new PopulationBuilder[]{
                sseReconnectScenario(subscription, numUsers)
                        .injectOpen(atOnceUsers(numUsers))
        };
    }

    @Override
    public void printInfo() {
        System.out.println("=== SSE 재연결 시나리오 ===");
        System.out.println("  유저 수: " + PlanLoader.getNumUsers());
        System.out.println("  섹션 전환 요청: " + PlanLoader.getTotalSectionMoveRequests());
        System.out.println("  좌석 점유 요청: " + PlanLoader.getTotalBookRequests());
    }

    private ScenarioBuilder sseReconnectScenario(SubscriptionHandler subscription, int numUsers) {
        return scenario("SSE 재연결 시나리오")
                .exec(loginAndStoreSession())
                .exec(prepareAndAssign(subscription))
                .exec(synchronizeUsers(numUsers))
                .exec(
                        foreach(session -> {
                            List<PlannedRequest> reqs = session.get("myRequests");
                            return reqs != null ? reqs : List.of();
                        }, "currentReq").on(
                                exec(expandCurrentReq()),
                                exec(waitUntilScheduledTime()),
                                exec(logRequestStart()),
                                exec(sendPlannedRequest(subscription)),
                                exec(logRequestResult())
                        )
                )
                .exec(subscription.close());
    }

    private ChainBuilder loginAndStoreSession() {
        return exec(BookingActions.setUpUserNum())
                .exec(BookingActions.setStaggeredLogin())
                .exec(BookingActions.waitBeforeStaggeredLogin())
                .exec(BookingActions.loginOrSetCookie())
                .exec(session -> {
                    if (!TEST_ACCOUNT_ALREADY_STORED) {
                        int userNum = session.getInt("userNum");
                        String sessionId = session.getString("sessionId");
                        if (sessionId != null && !sessionId.isEmpty()) {
                            SessionStore.setSharedSession(userNum, sessionId);
                        }
                    }
                    AsyncLogger.logf("유저 준비 완료: %d", session.getInt("userNum"));
                    return session;
                })
                .exec(BookingActions.waitAfterStaggeredLogin());
    }

    private ChainBuilder prepareAndAssign(SubscriptionHandler subscription) {
        return tryMax(10).on(exec(BookingActions.checkPermission())).exitHereIfFailed()
                .exec(BookingActions.setBookingAmount())
                .exec(BookingActions.waitBeforeSubscribe())
                .exec(session -> {
                    int userNum = session.getInt("userNum");
                    List<PlannedRequest> myRequests = PlanLoader.getUserPlans().get(userNum);
                    if (myRequests == null) myRequests = List.of();

                    // 첫 번째 요청의 section으로 초기 SSE 연결 (section 미지정 시 init pool → 좌석 데이터 미전송 함정 회피)
                    int initialSection = 0;
                    for (PlannedRequest req : myRequests) {
                        initialSection = req.isSectionMove() ? req.targetSection : req.section;
                        break;
                    }
                    return session.set("myRequests", myRequests).set("sseTargetSection", initialSection);
                })
                .exec(subscription.reconnectToSection(TARGET_EVENT));
    }

    private ChainBuilder synchronizeUsers(int numUsers) {
        return exec(session -> {
            int ready = requestReadyCount.incrementAndGet();
            AsyncLogger.logf("구독 완료: %d/%d", ready, numUsers);
            return session;
        })
        .exec(rendezVous(numUsers))
        .exec(session -> {
            simulationStartTime.compareAndSet(0, System.currentTimeMillis());
            return session.set("simStartTime", simulationStartTime.get());
        });
    }

    private ChainBuilder expandCurrentReq() {
        return exec(session -> {
            PlannedRequest req = session.get("currentReq");
            return session
                    .set("reqId", req.id)
                    .set("reqType", req.type.name())
                    .set("reqTimeMs", req.timeMs)
                    .set("reqSection", req.section)
                    .set("reqTargetSection", req.targetSection)
                    .set("reqSeat", req.seat)
                    .set("reqBody", req.requestBody)
                    .set("sectionSwitchStatus", 0)
                    .set("sectionSwitchResponseTime", 0L)
                    .set("responseStatus", 0)
                    .set("responseTime", 0L);
        });
    }

    private ChainBuilder waitUntilScheduledTime() {
        return exec(session -> {
            long waitTime = Math.max(0,
                    session.getLong("simStartTime") + session.getLong("reqTimeMs") - System.currentTimeMillis());
            return session.set("waitTimeMs", waitTime);
        })
        .pause(session -> Duration.ofMillis(session.getLong("waitTimeMs")));
    }

    private ChainBuilder logRequestStart() {
        return exec(session -> {
            long actualTime = System.currentTimeMillis() - session.getLong("simStartTime");
            AsyncLogger.logf(
                    "요청 시작 [%s] User%d type=%s planned=%dms actual=%dms",
                    session.getString("reqId"),
                    session.getInt("userNum"),
                    session.getString("reqType"),
                    session.getLong("reqTimeMs"),
                    actualTime
            );
            return session;
        });
    }

    private ChainBuilder sendPlannedRequest(SubscriptionHandler subscription) {
        return exec(
                doIf(session -> RequestType.SECTION_MOVE.name().equals(session.getString("reqType"))).then(
                        exec(session -> session.set("sectionSwitchStartTime", System.currentTimeMillis())),
                        exec(subscription.close()),
                        exec(session -> session.set("sseTargetSection", session.getInt("reqTargetSection"))),
                        exec(subscription.reconnectToSection(TARGET_EVENT)),
                        pause(Duration.ofMillis(500)),
                        exec(subscription.reloadSeatStatus()),
                        exec(session -> {
                            long elapsed = System.currentTimeMillis() - session.getLong("sectionSwitchStartTime");
                            return session
                                    .set("sectionSwitchStatus", 200)
                                    .set("sectionSwitchResponseTime", elapsed);
                        })
                )
        )
        .exec(
                doIf(session -> RequestType.BOOK.name().equals(session.getString("reqType"))).then(
                        exec(
                                http("계획 좌석 점유")
                                        .post("/booking")
                                        .body(StringBody("#{reqBody}"))
                                        .check(
                                                status().saveAs("responseStatus"),
                                                status().in(200, 201),
                                                responseTimeInMillis().saveAs("responseTime")
                                        )
                        )
                )
        );
    }

    private ChainBuilder logRequestResult() {
        return exec(session -> {
            String reqType = session.getString("reqType");
            boolean isSectionMove = RequestType.SECTION_MOVE.name().equals(reqType);
            int status = isSectionMove ? session.getInt("sectionSwitchStatus") : session.getInt("responseStatus");
            long responseTime = isSectionMove ? session.getLong("sectionSwitchResponseTime") : session.getLong("responseTime");

            boolean success = status == 200 || status == 201;
            AsyncLogger.logf(
                    "%s [%s] User%d type=%s section=%d target=%d status=%d response=%dms",
                    success ? "OK" : "KO",
                    session.getString("reqId"),
                    session.getInt("userNum"),
                    reqType,
                    session.getInt("reqSection"),
                    session.getInt("reqTargetSection"),
                    status,
                    responseTime
            );

            int total = PlanLoader.getTotalPlannedRequests();
            int completed = requestCompletedCount.incrementAndGet();
            if (completed % 50 == 0 || completed == total) {
                AsyncLogger.logf("요청 완료: %d/%d", completed, total);
            }

            return session;
        });
    }
}
