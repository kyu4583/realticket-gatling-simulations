package simulations.booking.scenario;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import simulations.booking.core.BookingActions;
import simulations.booking.subscription.SubscriptionHandler;

import static io.gatling.javaapi.core.CoreDsl.*;
import static simulations.config.Config.*;

/**
 * Dynamic 시나리오
 * 
 * 실시간 좌석 상태를 기반으로 동적으로 좌석을 선택하고 예약한다.
 * 충돌 시 재시도를 수행한다.
 * 시뮬레이션 결과가 Gatling을 실행하는 환경의 성능에 크게 영향을 받는다.
 */
public class DynamicScenario implements ScenarioExecutor {
    
    @Override
    public PopulationBuilder[] build(SubscriptionHandler subscription) {
        ScenarioBuilder scn = scenario("이벤트=" + TARGET_EVENT + " 동적 예매 시나리오")
                .exec(BookingActions.setStaggeredLogin())
                .exec(BookingActions.waitBeforeStaggeredLogin())
                .exec(BookingActions.setUpUserNum())
                .exec(BookingActions.loginOrSetCookie())
                .exec(BookingActions.waitAfterStaggeredLogin())
                .pause(session -> BookingActions.afterLoginDelay())

                .exec(BookingActions.waitBetweenActions())

                .exec(BookingActions.checkPermission()).exitHereIfFailed()
                .pause(session -> BookingActions.beforeBookingAmountSetDelay())
                .exec(BookingActions.setBookingAmount())
                .exec(BookingActions.subscribeSeats(subscription))
                .exec(BookingActions.waitAfterSubscribe())
                .exec(BookingActions.bookSeatsWithRetry(subscription))

                .exec(BookingActions.saveBookedSeatsAsJson())
                .exec(BookingActions.waitBetweenActions())
                .exec(BookingActions.confirmReservation(true))

                .exec(subscription.close());
        
        return new PopulationBuilder[] {
                scn.injectOpen(atOnceUsers(DYNAMIC_USER_COUNT))
        };
    }
    
    @Override
    public void printInfo() {
        System.out.println("=== Dynamic 시나리오 ===");
        System.out.println("  유저 수: " + DYNAMIC_USER_COUNT);
        System.out.println("  예매 수량: " + (FIXED_BOOKING_AMOUNT >= 0 ? FIXED_BOOKING_AMOUNT : "랜덤(1~4)"));
        System.out.println("  최대 재시도: " + MAX_RETRY_IN_BOOKING_CONFLICT);
    }
}
