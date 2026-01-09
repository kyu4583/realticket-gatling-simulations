package simulations.booking.scenario;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import simulations.util.AsyncLogger;
import simulations.booking.core.BookingActions;
import simulations.booking.core.PlanLoader;
import simulations.booking.core.PlanLoader.PlannedRequest;
import simulations.booking.core.SessionStore;
import simulations.booking.subscription.SubscriptionHandler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static simulations.config.Config.*;

/**
 * Parallel 시나리오
 *
 * no_collision 모드에서 정확한 타이밍으로 요청을 보내기 위한 시나리오.
 *
 * 구조:
 * - Setup 세션 (N개): 로그인, 권한 확인, SSE 구독 유지
 * - Request 세션 (N×M개): 각각 단일 요청만 담당, 정확한 타이밍에 실행
 */
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
            System.out.println("⚠️ 경고: no_collision 모드가 아닙니다. Static 시나리오를 권장합니다.");
        }

        PlanLoader.initializeParallelData();

        int numUsers = PlanLoader.getNumUsers();
        int totalRequests = PlanLoader.getTotalPlannedRequests();

        return new PopulationBuilder[] {
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

        System.out.println("=== Parallel 시나리오 ===");
        System.out.println("  Setup 세션: " + numUsers + "개");
        System.out.println("  Request 세션: " + totalRequests + "개");
        System.out.println("  총 세션: " + (numUsers + totalRequests) + "개");
        System.out.println("  유저당 좌석: " + PlanLoader.getSeatsPerUser());
        System.out.println("  no_collision 모드: " + PlanLoader.isNoCollisionMode());
    }

    // ========== Setup 시나리오 ==========

    private ScenarioBuilder setupUsersScenario(SubscriptionHandler subscription, int numUsers, int totalRequests) {
        return scenario("Setup Users")
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
                            AsyncLogger.logf("Setup 유저 %d: 세션 저장 완료", userNum);
                        } else {
                            AsyncLogger.logf("⚠️ Setup 유저 %d: 세션 없음!", userNum);
                        }
                    }

                    int completed = setupCompletedCount.incrementAndGet();
                    AsyncLogger.logf("Setup 진행: %d/%d", completed, numUsers);
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
                    AsyncLogger.logf("구독 완료: %d/%d", completed, numUsers);
                    return session;
                });
    }

    private ChainBuilder waitForRequestsCompletion(int totalRequests) {
        return asLongAs(session -> requestCompletedCount.get() < totalRequests)
                .on(pause(Duration.ofMillis(500)))
                .exec(session -> {
                    AsyncLogger.logf("Setup 유저 %d: 종료", session.getInt("userNum"));
                    return session;
                });
    }

    // ========== Request 시나리오 ==========

    private ScenarioBuilder parallelRequestScenario(int numUsers, int totalRequests) {
        return scenario("Parallel Requests")
                .exec(waitForSubscriptions(numUsers))
                .exec(BookingActions.waitAfterSubscribe())
                .exec(assignRequestToSession(totalRequests))
                .doIf(session -> session.getBoolean("hasRequest")).then(
                        exec(executeTimedRequest(totalRequests))
                );
    }

    private ChainBuilder waitForSubscriptions(int numUsers) {
        return asLongAs(session -> subscribeCompletedCount.get() < numUsers)
                .on(pause(Duration.ofMillis(100)));
    }

    private ChainBuilder assignRequestToSession(int totalRequests) {
        List<PlannedRequest> allRequests = PlanLoader.getAllRequestsSorted();
        int[] userIds = PlanLoader.getRequestUserIds();

        return exec(session -> {
            int idx = requestFeederIndex.getAndIncrement();
            if (idx >= allRequests.size()) {
                AsyncLogger.logf("⚠️ 요청 인덱스 초과: %d", idx);
                return session.set("hasRequest", false);
            }

            PlannedRequest req = allRequests.get(idx);
            int userId = userIds[idx];
            String sessionId = SessionStore.getSharedSession(userId);

            if (sessionId == null || sessionId.isEmpty()) {
                AsyncLogger.logf("⚠️ Request [%s]: User%d 세션 없음!", req.id, userId);
                sessionId = "";
            }

            return session
                    .set("hasRequest", true)
                    .set("reqUserId", userId)
                    .set("reqId", req.id)
                    .set("reqTimeMs", req.timeMs)
                    .set("reqSection", req.section)
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
                .exec(sendBookingRequest())
                .exec(logRequestResult(totalRequests));
    }

    private ChainBuilder synchronizeRequests(int totalRequests) {
        return exec(session -> {
            int ready = requestReadyCount.incrementAndGet();
            if (ready % 100 == 0 || ready == totalRequests) {
                AsyncLogger.logf("Request 준비: %d/%d", ready, totalRequests);
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
            long now = System.currentTimeMillis();
            long waitTime = Math.max(0, targetTime - now);
            return session.set("waitTimeMs", waitTime);
        })
                .pause(session -> Duration.ofMillis(session.getLong("waitTimeMs")));
    }

    private ChainBuilder logRequestStart() {
        return exec(session -> {
            long simStartTime = session.getLong("simStartTime");
            long actualTime = System.currentTimeMillis() - simStartTime;
            AsyncLogger.logf("요청 시작 [%s] User%d - 계획: %dms, 실제: %dms, 편차: %dms",
                    session.getString("reqId"),
                    session.getInt("reqUserId"),
                    session.getLong("reqTimeMs"),
                    actualTime,
                    actualTime - session.getLong("reqTimeMs"));
            return session;
        });
    }

    private ChainBuilder sendBookingRequest() {
        return tryMax(10).on(
                exec(
                        http("좌석 점유")
                                .post("/booking")
                                .body(StringBody("#{reqBody}"))
                                .check(
                                        status().saveAs("responseStatus"),
                                        status().in(200, 201),
                                        responseTimeInMillis().saveAs("responseTime")
                                )
                )
        );
    }

    private ChainBuilder logRequestResult(int totalRequests) {
        return exec(session -> {
            String reqId = session.getString("reqId");
            int status = session.getInt("responseStatus");
            int userId = session.getInt("reqUserId");
            long responseTime = session.getLong("responseTime");

            String symbol = (status == 200 || status == 201) ? "✓" : "✗";
            AsyncLogger.logf("%s [%s] User%d Sec:%d Seat:%d 상태:%d 응답:%dms",
                    symbol, reqId, userId,
                    session.getInt("reqSection"),
                    session.getInt("reqSeat"),
                    status, responseTime);

            int completed = requestCompletedCount.incrementAndGet();
            if (completed % 100 == 0 || completed == totalRequests) {
                AsyncLogger.logf("Request 완료: %d/%d", completed, totalRequests);
            }

            return session;
        });
    }
}