package in.ybuilds.shortify.service;

import in.ybuilds.shortify.model.RateLimitData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${shortify.rate-limit.request-per-minute}")
    private int requestPerMinute;
    @Value("${shortify.rate-limit.request-per-hour}")
    private int requestPerHour;

    private final Map<String, RateLimitData> rateLimitData = new ConcurrentHashMap<>();
    private static final String REDIS_KEY_PREFIX = "ratelimit:";

    public boolean isAllowed(String clientIp) {
        String redisKey = REDIS_KEY_PREFIX + clientIp;

        LocalDateTime now = LocalDateTime.now();

        RateLimitData data = getRateLimitData(redisKey);

        if(data == null) {
            data = rateLimitData.computeIfAbsent(clientIp, k -> RateLimitData
                    .builder()
                    .minuteCount(0)
                    .hourCount(0)
                    .minuteWindowStart(now)
                    .hourWindowStart(now).
                    build());
        }

        if(isWithinMinuteWindow(data, now)) {
            if(data.getMinuteCount() >= requestPerMinute) {
                log.warn("Minute limit exceeded for {}", clientIp);
                return false;
            }
        } else {
            data.setMinuteCount(0);
            data.setMinuteWindowStart(now);
        }

        if(isWithinHourWindow(data, now)) {
            if(data.getHourCount() >= requestPerHour) {
                log.warn("Hour limit exceeded for {}", clientIp);
                return false;
            }
        } else {
            data.setHourCount(0);
            data.setHourWindowStart(now);
        }

        data.setMinuteCount(data.getMinuteCount() + 1);
        data.setHourCount(data.getHourCount() + 1);

        saveRateLimitDataToRedis(redisKey, data);

        return true;
    }

    private void saveRateLimitDataToRedis(String redisKey, RateLimitData data) {
        try {
            redisTemplate.opsForValue().set(redisKey, data, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Failed to save rate limit data to Redis: {}", e.getMessage());
        }
    }

    private boolean isWithinHourWindow(RateLimitData data, LocalDateTime now) {
        return data.getHourWindowStart() != null && ChronoUnit.HOURS.between(data.getHourWindowStart(), now) < 1;
    }

    private boolean isWithinMinuteWindow(RateLimitData data, LocalDateTime now) {
        return data.getMinuteWindowStart() != null && ChronoUnit.MINUTES.between(data.getMinuteWindowStart(), now) < 1;
    }

    private RateLimitData getRateLimitData(String redisKey) {
        try {
            Object value = redisTemplate.opsForValue().get(redisKey);
            if (value == null) {
                return null;
            }
            return objectMapper.convertValue(value, RateLimitData.class);
        } catch (Exception e) {
            log.warn("Failed to get rate limit data from Redis: {}", e.getMessage());
            return null;
        }
    }

    public int getRemainingRequests(String clientIp) {
        String redisKey = REDIS_KEY_PREFIX + clientIp;

        RateLimitData data = getRateLimitData(redisKey);

        if(data == null) {
            return requestPerMinute;
        }

        LocalDateTime now = LocalDateTime.now();

        if(!isWithinMinuteWindow(data, now)) {
            return requestPerMinute;
        }

        return Math.max(0, requestPerMinute - data.getMinuteCount());
    }

    public long getTimeUntilReset(String clientIp) {
        String redisKey = REDIS_KEY_PREFIX + clientIp;

        RateLimitData data = getRateLimitData(redisKey);

        if(data == null) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();

        if(data.getMinuteCount() >= requestPerMinute) {
            LocalDateTime nextMinute = data.getMinuteWindowStart().plusMinutes(1);
            return ChronoUnit.SECONDS.between(now, nextMinute);
        }

        if(data.getHourCount() >= requestPerHour) {
            LocalDateTime nextHour = data.getHourWindowStart().plusHours(1);
            return ChronoUnit.SECONDS.between(now, nextHour);
        }

        return 0;
    }
}
