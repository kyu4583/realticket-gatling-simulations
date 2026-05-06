package simulations.booking.scenario;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import simulations.booking.core.BookingActions;
import simulations.booking.core.PlanLoader;
import simulations.booking.core.PlanLoader.PlannedRequest;
import simulations.booking.core.PlanLoader.RequestType;
import simulations.booking.subscription.SubscriptionHandler;
import simulations.util.AsyncLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static simulations.config.Config.TARGET_EVENT;

public class StaticScenario implements ScenarioExecutor {

    private static final AtomicLong simulationStartTime = new AtomicLong(0);
    private static final AtomicInteger readyUserCount = new AtomicInteger(0);

    @Override
    public PopulationBuilder[] build(SubscriptionHandler subscription) {
        PlanLoader.load();

        int numUsers = PlanLoader.getNumUsers();

        ScenarioBuilder scn = scenario("이벤트 " + TARGET_EVENT + " 정적 예매 시나리오")
                .exec(BookingActions.setUpUserNum())
                .exec(BookingActions.setStaggeredLogin())
                .exec(BookingActions.waitBeforeStaggeredLogin())
                .exec(BookingActions.loginOrSetCookie())
                .exec(BookingActions.waitAfterStaggeredLogin())

                .tryMax(10).on(exec(BookingActions.checkPermission())).exitHereIfFailed()
                .exec(BookingActions.setBookingAmount())
                .exec(BookingActions.subscribeSeats(subscription))
                .exec(BookingActions.waitAfterSubscribe())

                .exec(loadUserPlan())
                .exec(waitForAllUsersReady(numUsers))
                .exec(staticRequestLoop())

                .exec(BookingActions.saveBookedSeatsAsJson())
                .exec(BookingActions.confirmReservation(true))
                .exec(subscription.close());

        return new PopulationBuilder[]{
                scn.injectOpen(atOnceUsers(numUsers))
        };
    }

    @Override
    public void printInfo() {
        System.out.println("=== 정적 시나리오 ===");
        System.out.println("  유저 수: " + PlanLoader.getNumUsers());
        System.out.println("  유저당 좌석: " + PlanLoader.getSeatsPerUser());
        System.out.println("  충돌 그룹: " + PlanLoader.getCollisionGroups().size());
        System.out.println("  계획 요청: " + PlanLoader.getTotalPlannedRequests());
        System.out.println("  좌석 점유 요청: " + PlanLoader.getTotalBookRequests());
        System.out.println("  섹션 전환 요청: " + PlanLoader.getTotalSectionMoveRequests());
    }

    private ChainBuilder loadUserPlan() {
        return exec(session -> {
            int userNum = session.getInt("userNum");
            List<PlannedRequest> myRequests = PlanLoader.getUserPlans().get(userNum);

            if (myRequests == null || myRequests.isEmpty()) {
                AsyncLogger.log("유저 " + userNum + "의 계획 요청이 없습니다.");
                return session
                        .set("myRequests", Collections.emptyList())
                        .set("currentRequestIndex", 0)
                        .set("hasMoreRequests", false)
                        .set("bookedSeats", new ArrayList<int[]>());
            }

            return session
                    .set("myRequests", new ArrayList<>(myRequests))
                    .set("currentRequestIndex", 0)
                    .set("hasMoreRequests", true)
                    .set("bookedSeats", new ArrayList<int[]>());
        });
    }

    private ChainBuilder waitForAllUsersReady(int numUsers) {
        return exec(session -> {
            int userNum = session.getInt("userNum");
            int ready = readyUserCount.incrementAndGet();
            AsyncLogger.logf("유저 %d 준비 완료 (%d/%d)", userNum, ready, numUsers);
            return session;
        })
                .rendezVous(numUsers)
                .exec(session -> {
                    if (simulationStartTime.compareAndSet(0, System.currentTimeMillis() + 500)) {
                        AsyncLogger.log("시뮬레이션 시작 시간: " + simulationStartTime.get());
                    }
                    return session.set("simStartTime", simulationStartTime.get());
                });
    }

    private ChainBuilder staticRequestLoop() {
        return asLongAs(session -> session.getBoolean("hasMoreRequests")).on(
                exec(loadNextRequest()),
                pause(session -> Duration.ofMillis(session.getLong("waitTimeMs"))),
                doIf(session -> RequestType.SECTION_MOVE.name().equals(session.getString("currentReqType"))).then(
                        exec(BookingActions.switchToCurrentRequestTargetSection()).exitHereIfFailed(),
                        exec(advanceAfterSectionMove())
                ),
                doIf(session -> RequestType.BOOK.name().equals(session.getString("currentReqType"))).then(
                        exec(BookingActions.switchToCurrentRequestSection()).exitHereIfFailed(),
                        exec(sendCurrentBookingRequest()),
                        exec(recordBookResultAndAdvance()),
                        exec(handleCollisionLoserChain())
                )
        );
    }

    private java.util.function.Function<Session, Session> loadNextRequest() {
        return session -> {
            List<PlannedRequest> myRequests = session.get("myRequests");
            int index = session.getInt("currentRequestIndex");

            if (index >= myRequests.size()) {
                return session.set("hasMoreRequests", false).set("waitTimeMs", 0L);
            }

            PlannedRequest request = myRequests.get(index);
            long simStartTime = session.getLong("simStartTime");
            long targetTime = simStartTime + request.timeMs;
            long waitTime = Math.max(0, targetTime - System.currentTimeMillis());

            AsyncLogger.logf(
                    "계획 요청 [%s] type=%s section=%d target=%d seat=%d wait=%dms",
                    request.id,
                    request.type,
                    request.section,
                    request.targetSection,
                    request.seat,
                    waitTime
            );

            return session
                    .set("waitTimeMs", waitTime)
                    .set("currentReqId", request.id)
                    .set("currentReqType", request.type.name())
                    .set("currentReqSection", request.section)
                    .set("currentReqTargetSection", request.targetSection)
                    .set("currentReqSeat", request.seat)
                    .set("currentReqBody", request.requestBody);
        };
    }

    private ActionBuilder sendCurrentBookingRequest() {
        return http("계획 좌석 점유")
                .post("/booking")
                .body(StringBody("#{currentReqBody}"))
                .check(
                        status().saveAs("lastResponseStatus"),
                        status().in(200, 201)
                );
    }

    private java.util.function.Function<Session, Session> advanceAfterSectionMove() {
        return session -> advanceRequestIndex(session).set("pendingCollisionId", null);
    }

    private java.util.function.Function<Session, Session> recordBookResultAndAdvance() {
        Map<String, String> requestToCollision = PlanLoader.getRequestToCollision();

        return session -> {
            int status = session.getInt("lastResponseStatus");
            boolean success = status == 200 || status == 201;
            String reqId = session.getString("currentReqId");
            String collisionId = requestToCollision.get(reqId);

            Session updatedSession = session;

            if (success) {
                List<int[]> bookedSeats = session.get("bookedSeats");
                bookedSeats.add(new int[]{
                        session.getInt("currentReqSection"),
                        session.getInt("currentReqSeat")
                });
                updatedSession = session
                        .set("bookedSeats", bookedSeats)
                        .set("pendingCollisionId", null);
            } else {
                updatedSession = session.set("pendingCollisionId", collisionId);
            }

            return advanceRequestIndex(updatedSession);
        };
    }

    private Session advanceRequestIndex(Session session) {
        int nextIndex = session.getInt("currentRequestIndex") + 1;
        List<PlannedRequest> myRequests = session.get("myRequests");

        return session
                .set("currentRequestIndex", nextIndex)
                .set("hasMoreRequests", nextIndex < myRequests.size());
    }

    private ChainBuilder handleCollisionLoserChain() {
        Map<String, ConcurrentLinkedQueue<PlannedRequest>> loserQueues = PlanLoader.getLoserRequestQueues();
        Map<String, String> requestToCollision = PlanLoader.getRequestToCollision();

        return asLongAs(session -> session.get("pendingCollisionId") != null).on(
                exec(session -> {
                    String collisionId = session.getString("pendingCollisionId");
                    ConcurrentLinkedQueue<PlannedRequest> queue = loserQueues.get(collisionId);

                    if (queue == null || queue.isEmpty()) {
                        AsyncLogger.logf("충돌 그룹 %s: 더 이상 대체 요청 없음", collisionId);
                        return session.set("pendingCollisionId", null);
                    }

                    PlannedRequest loserReq = queue.poll();
                    if (loserReq == null) {
                        return session.set("pendingCollisionId", null);
                    }

                    AsyncLogger.logf(
                            "충돌 대체 요청 [%s] section=%d seat=%d",
                            loserReq.id,
                            loserReq.section,
                            loserReq.seat
                    );

                    return session
                            .set("loserReqId", loserReq.id)
                            .set("loserReqSection", loserReq.section)
                            .set("loserReqSeat", loserReq.seat)
                            .set("loserReqBody", loserReq.requestBody);
                }),
                doIf(session -> session.get("pendingCollisionId") != null).then(
                        exec(BookingActions.switchToLoserRequestSection()).exitHereIfFailed(),
                        exec(
                                http("충돌 대체 좌석 점유")
                                        .post("/booking")
                                        .body(StringBody("#{loserReqBody}"))
                                        .check(
                                                status().saveAs("loserResponseStatus"),
                                                status().in(200, 201)
                                        )
                        ),
                        exec(session -> {
                            int status = session.getInt("loserResponseStatus");
                            boolean success = status == 200 || status == 201;
                            String reqId = session.getString("loserReqId");
                            int section = session.getInt("loserReqSection");
                            int seat = session.getInt("loserReqSeat");

                            if (success) {
                                List<int[]> bookedSeats = session.get("bookedSeats");
                                bookedSeats.add(new int[]{section, seat});
                                return session
                                        .set("bookedSeats", bookedSeats)
                                        .set("pendingCollisionId", null);
                            } else {
                                String nextCollisionId = requestToCollision.get(reqId);
                                return session.set("pendingCollisionId", nextCollisionId);
                            }
                        })
                )
        );
    }
}
