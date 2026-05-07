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

public class ParallelScenario implements ScenarioExecutor {

    private static final AtomicInteger setupCompletedCount = new AtomicInteger(0);
    private static final AtomicInteger subscribeCompletedCount = new AtomicInteger(0);
    private static final AtomicInteger requestReadyCount = new AtomicInteger(0);
    private static final AtomicInteger requestCompletedCount = new AtomicInteger(0);
    private static final AtomicInteger requestFeederIndex = new AtomicInteger(0);
    private static final AtomicLong simulationStartTime = new AtomicLong(0);

    @Override
    public PopulationBuilder[] build(SubscriptionHandler subscription) {
        PlanLoader.load();

        if (!PlanLoader.isNoCollisionMode()) {
            System.out.println("경고: 병렬 모드는 no_collision 계획에 맞춰져 있습니다.");
        }

        PlanLoader.initializeParallelData();

        int numUsers = PlanLoader.getNumUsers();
        int totalRequests = PlanLoader.getTotalPlannedRequests();

        return new PopulationBuilder[]{
                setupUsersScenario(subscription, numUsers, totalRequests)
                        .injectOpen(atOnceUsers(numUsers)),
                parallelRequestScenario(numUsers, totalRequests)
                        .injectOpen(atOnceUsers(totalRequests))
        };
    }

    @Override
    public void printInfo() {
        int numUsers = PlanLoader.getNumUsers();
        int totalRequests = PlanLoader.getTotalPlannedRequests();

        System.out.println("=== 병렬 시나리오 ===");
        System.out.println("  준비 세션: " + numUsers);
        System.out.println("  요청 세션: " + totalRequests);
        System.out.println("  전체 세션: " + (numUsers + totalRequests));
        System.out.println("  유저당 좌석: " + PlanLoader.getSeatsPerUser());
        System.out.println("  no_collision 모드: " + PlanLoader.isNoCollisionMode());
        System.out.println("  섹션 전환 요청: " + PlanLoader.getTotalSectionMoveRequests());
    }

    private ScenarioBuilder setupUsersScenario(SubscriptionHandler subscription, int numUsers, int totalRequests) {
        return scenario("유저 준비")
                .exec(loginAndStoreSession(numUsers))
                .exec(prepareAndSubscribe(subscription, numUsers))
                .exec(waitForRequestsCompletion(totalRequests))
                .exec(subscription.close());
    }

    private ChainBuilder loginAndStoreSession(int numUsers) {
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
                            AsyncLogger.logf("유저 준비 %d: 세션 저장 완료", userNum);
                        } else {
                            AsyncLogger.logf("유저 준비 %d: 세션 없음", userNum);
                        }
                    }

                    int completed = setupCompletedCount.incrementAndGet();
                    AsyncLogger.logf("유저 준비 진행: %d/%d", completed, numUsers);
                    return session;
                })
                .exec(BookingActions.waitAfterStaggeredLogin());
    }

    private ChainBuilder prepareAndSubscribe(SubscriptionHandler subscription, int numUsers) {
        return tryMax(10).on(exec(BookingActions.checkPermission())).exitHereIfFailed()
                .exec(BookingActions.setBookingAmount())
                .exec(BookingActions.waitBeforeSubscribe())
                .exec(BookingActions.subscribeSeats(subscription))
                .exec(session -> {
                    int completed = subscribeCompletedCount.incrementAndGet();
                    AsyncLogger.logf("구독 준비 완료: %d/%d", completed, numUsers);
                    return session;
                });
    }

    private ChainBuilder waitForRequestsCompletion(int totalRequests) {
        return asLongAs(session -> requestCompletedCount.get() < totalRequests)
                .on(pause(Duration.ofMillis(500)))
                .exec(session -> {
                    AsyncLogger.logf("유저 준비 %d: 종료", session.getInt("userNum"));
                    return session;
                });
    }

    private ScenarioBuilder parallelRequestScenario(int numUsers, int totalRequests) {
        return scenario("병렬 요청")
                .exec(waitForSubscriptions(numUsers))
                .exec(BookingActions.waitAfterSubscribe())
                .exec(assignRequestToSession())
                .doIf(session -> session.getBoolean("hasRequest")).then(
                        exec(executeTimedRequest(totalRequests))
                );
    }

    private ChainBuilder waitForSubscriptions(int numUsers) {
        return asLongAs(session -> subscribeCompletedCount.get() < numUsers)
                .on(pause(Duration.ofMillis(100)));
    }

    private ChainBuilder assignRequestToSession() {
        List<PlannedRequest> allRequests = PlanLoader.getAllRequestsSorted();
        int[] userIds = PlanLoader.getRequestUserIds();

        return exec(session -> {
            int idx = requestFeederIndex.getAndIncrement();
            if (idx >= allRequests.size()) {
                AsyncLogger.logf("요청 인덱스 초과: %d", idx);
                return session.set("hasRequest", false);
            }

            PlannedRequest req = allRequests.get(idx);
            int userId = userIds[idx];
            String sessionId = SessionStore.getSharedSession(userId);

            if (sessionId == null || sessionId.isEmpty()) {
                AsyncLogger.logf("요청 [%s]: User%d 저장 세션 없음", req.id, userId);
                sessionId = "";
            }

            return session
                    .set("hasRequest", true)
                    .set("reqUserId", userId)
                    .set("reqId", req.id)
                    .set("reqType", req.type.name())
                    .set("reqTimeMs", req.timeMs)
                    .set("reqSection", req.section)
                    .set("reqTargetSection", req.targetSection)
                    .set("reqSeat", req.seat)
                    .set("reqBody", req.requestBody)
                    .set("sessionId", sessionId);
        });
    }

    private ChainBuilder executeTimedRequest(int totalRequests) {
        return exec(addCookie(Cookie("SID", "#{sessionId}").withPath("/")))
                .exec(synchronizeRequests(totalRequests))
                .exec(waitUntilScheduledTime())
                .exec(logRequestStart())
                .exec(session -> session
                        .set("sectionSwitchStatus", 0)
                        .set("sectionSwitchResponseTime", 0L)
                        .set("responseStatus", 0)
                        .set("responseTime", 0L))
                .exec(sendPlannedRequest())
                .exec(logRequestResult(totalRequests));
    }

    private ChainBuilder synchronizeRequests(int totalRequests) {
        return exec(session -> {
            int ready = requestReadyCount.incrementAndGet();
            if (ready % 100 == 0 || ready == totalRequests) {
                AsyncLogger.logf("요청 준비: %d/%d", ready, totalRequests);
            }
            return session;
        })
                .exec(rendezVous(totalRequests))
                .exec(session -> {
                    simulationStartTime.compareAndSet(0, System.currentTimeMillis());
                    return session.set("simStartTime", simulationStartTime.get());
                });
    }

    private ChainBuilder waitUntilScheduledTime() {
        return exec(session -> {
            long simStartTime = session.getLong("simStartTime");
            long reqTimeMs = session.getLong("reqTimeMs");
            long targetTime = simStartTime + reqTimeMs;
            long waitTime = Math.max(0, targetTime - System.currentTimeMillis());
            return session.set("waitTimeMs", waitTime);
        })
                .pause(session -> Duration.ofMillis(session.getLong("waitTimeMs")));
    }

    private ChainBuilder logRequestStart() {
        return exec(session -> {
            long actualTime = System.currentTimeMillis() - session.getLong("simStartTime");
            AsyncLogger.logf(
                    "요청 시작 [%s] User%d type=%s planned=%dms actual=%dms delta=%dms",
                    session.getString("reqId"),
                    session.getInt("reqUserId"),
                    session.getString("reqType"),
                    session.getLong("reqTimeMs"),
                    actualTime,
                    actualTime - session.getLong("reqTimeMs")
            );
            return session;
        });
    }

    private ChainBuilder sendPlannedRequest() {
        return tryMax(10).on(
                doIf(session -> RequestType.SECTION_MOVE.name().equals(session.getString("reqType"))).then(
                        exec(BookingActions.switchToReqTargetSection())
                ),
                doIf(session -> RequestType.BOOK.name().equals(session.getString("reqType"))).then(
                        exec(BookingActions.switchToReqSection()),
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

    private ChainBuilder logRequestResult(int totalRequests) {
        return exec(session -> {
            String reqType = session.getString("reqType");
            int status = RequestType.SECTION_MOVE.name().equals(reqType)
                    ? session.getInt("sectionSwitchStatus")
                    : session.getInt("responseStatus");
            long responseTime = RequestType.SECTION_MOVE.name().equals(reqType)
                    ? session.getLong("sectionSwitchResponseTime")
                    : session.getLong("responseTime");

            boolean success = status == 200 || status == 201;
            AsyncLogger.logf(
                    "%s [%s] User%d type=%s section=%d target=%d seat=%d status=%d response=%dms",
                    success ? "OK" : "KO",
                    session.getString("reqId"),
                    session.getInt("reqUserId"),
                    reqType,
                    session.getInt("reqSection"),
                    session.getInt("reqTargetSection"),
                    session.getInt("reqSeat"),
                    status,
                    responseTime
            );

            int completed = requestCompletedCount.incrementAndGet();
            if (completed % 100 == 0 || completed == totalRequests) {
                AsyncLogger.logf("요청 완료: %d/%d", completed, totalRequests);
            }

            return session;
        });
    }
}
