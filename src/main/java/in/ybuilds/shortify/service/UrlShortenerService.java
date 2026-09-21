package in.ybuilds.shortify.service;

import in.ybuilds.shortify.dto.ShortenUrlRequest;
import in.ybuilds.shortify.dto.ShortenUrlResponse;
import in.ybuilds.shortify.model.ClickEvent;
import in.ybuilds.shortify.model.UrlData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.swing.text.html.Option;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShortenerService {
    private final RedisTemplate<String, Object> redisTemplate;

    private final Map<String, UrlData> urlMapping = new ConcurrentHashMap<>();
    private final Map<String, List<ClickEvent>> clickAnalytics = new ConcurrentHashMap<>();

    @Value("${shortify.base-url}")
    private String baseUrl;
    @Value("${shortify.short-code.length}")
    private int shortCodeLength;
    @Value("${shortify.short-code.max-attempts}")
    private int maxGenerationAttempts;
    @Value("${shortify.cache.ttl-minutes}")
    private int cacheTtl;

    private static final String BASE_62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String clientIp) {
        String shortCode = request.getCustomAlias();

        if(shortCode == null || shortCode.trim().isEmpty()) {
            shortCode = generateUniqueShortCode();
        } else {
            shortCode = shortCode.trim();
            if(shortCodeExists(shortCode)) {
                throw new IllegalArgumentException("Custom alias already exists: " + shortCode);
            }
        }

        UrlData urlData = UrlData.builder()
                .originalUrl(request.getOriginalUrl())
                .shortCode(shortCode)
                .expiresAt(request.getExpiresAfter())
                .createdAt(LocalDateTime.now())
                .createdBy(clientIp)
                .clickCount(0)
                .isActive(true)
                .clickEvents(new ArrayList<>())
                .build();
        
        urlMapping.put(shortCode, urlData);
        clickAnalytics.put(shortCode, new ArrayList<>());
        
        cacheUrl(shortCode, request.getOriginalUrl());
        
        log.info("Create short URL: {} -> {}", shortCode, request.getOriginalUrl());
        
        return ShortenUrlResponse.builder()
                .shortUrl(buildShortUrl(shortCode))
                .shortCode(shortCode)
                .originalUrl(request.getOriginalUrl())
                .createdAt(urlData.getCreatedAt())
                .expiresAt(urlData.getExpiresAt())
                .build();
    }

    private String buildShortUrl(String shortCode) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        return normalizedBaseUrl + "/api/" + shortCode;
    }

    private void cacheUrl(String shortCode, String originalUrl) {
        try {
            redisTemplate.opsForValue().set("url:" + shortCode, originalUrl, cacheTtl, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to cache URL for {}: {}", shortCode, e.getMessage());
        }
    }

    private boolean shortCodeExists(String shortCode) {
        return urlMapping.containsKey(shortCode);
    }

    private String generateUniqueShortCode() {
        for(int attempt=0; attempt<maxGenerationAttempts; attempt++) {
            String code = generateRandomBase62();

            if(!shortCodeExists(code)) {
                return code;
            }
        }

        throw new RuntimeException("Failed to generate unique short code after " + maxGenerationAttempts + " attempts");
    }

    private String generateRandomBase62() {
        StringBuilder stringBuilder = new StringBuilder();

        for(int i=0; i<shortCodeLength; i++) {
            int index = (int)(Math.random() * BASE_62_CHARS.length());
            stringBuilder.append(BASE_62_CHARS.charAt(index));
        }

        return stringBuilder.toString();
    }

    public Optional<String> getOriginalUrl(String shortCode) {
        String cachedUrl = getCachedUrl(shortCode);

        if(cachedUrl != null) {
            return Optional.of(cachedUrl);
        }

        UrlData urlData = urlMapping.get(shortCode);
        if(urlData != null && urlData.isActive()) {
            if(isExpired(urlData)) {
                urlData.setActive(false);
                return Optional.empty();
            }

            cacheUrl(shortCode, urlData.getOriginalUrl());
            return Optional.of(urlData.getOriginalUrl());
        }

        return Optional.empty();
    }

    private boolean isExpired(UrlData urlData) {
        return urlData.getExpiresAt() != null && urlData.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String getCachedUrl(String shortCode) {
        try {
            return (String) redisTemplate.opsForValue().get("url:" + shortCode);
        } catch (Exception e) {
            log.warn("Failed to read cached URL for {}: {}", shortCode, e.getMessage());
            return null;
        }
    }

    public void recordClick(String shortCode, String clientIp, String userAgent, String referrer) {
        UrlData urlData = urlMapping.get(shortCode);

        if(urlData != null && urlData.isActive()) {
            urlData.setClickCount(urlData.getClickCount() + 1);

            ClickEvent clickEvent = ClickEvent.builder()
                    .timestamp(LocalDateTime.now())
                    .ipAddress(clientIp)
                    .userAgent(userAgent)
                    .referrer(referrer)
                    .build();

            clickAnalytics.get(shortCode).add(clickEvent);

            log.debug("Recorded click event for short code {}", shortCode);
        }
    }
}
