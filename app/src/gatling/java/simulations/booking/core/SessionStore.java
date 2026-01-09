package simulations.booking.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionStore {

    private static final Map<String, String> storedSessionIds = new HashMap<>();
    
    private static boolean loaded = false;
    
    private SessionStore() {}
    
    /**
     * 저장된 세션 ID 로드 (stored_test_account_list.json)
     */
    public static synchronized void loadStoredSessions() {
        if (loaded) return;
        
        ObjectMapper mapper = new ObjectMapper();
        try {
            var inputStream = SessionStore.class.getResourceAsStream("/stored_test_account_list.json");
            
            if (inputStream == null) {
                throw new RuntimeException("리소스 파일을 찾을 수 없음: /stored_test_account_list.json");
            }
            
            Map<String, String> sessions = mapper.readValue(inputStream, new TypeReference<>() {});
            storedSessionIds.putAll(sessions);
            inputStream.close();
            
            System.out.println("저장된 세션 ID " + sessions.size() + "개 로드 완료");
            loaded = true;
            
        } catch (Exception e) {
            System.err.println("세션 ID 파일 로드 실패: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 사전 저장된 테스트 계정 ID 조회
     */
    public static String getStoredTestAccountSession(int userNum) {
        return storedSessionIds.get("test" + userNum);
    }
}
