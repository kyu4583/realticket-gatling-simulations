package simulations.booking.scenario;

import io.gatling.javaapi.core.PopulationBuilder;
import simulations.booking.subscription.SubscriptionHandler;

public interface ScenarioExecutor {
    
    /**
     * 시나리오 빌드 및 PopulationBuilder 배열 반환
     * 
     * @param subscription 구독 핸들러
     * @return setUp()에 전달할 PopulationBuilder 배열
     */
    PopulationBuilder[] build(SubscriptionHandler subscription);

    /**
     * 시나리오 정보 출력
     */
    void printInfo();
}
