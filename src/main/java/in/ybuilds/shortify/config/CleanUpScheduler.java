package in.ybuilds.shortify.config;

import in.ybuilds.shortify.service.UrlShortenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CleanUpScheduler {
    private final UrlShortenerService urlShortenerService;

    @Value("${shortify.cleanup.interval-minutes}")
    private int cleanUpIntervalMinutes;

    @Scheduled(fixedDelayString = "#{${shortify.cleanup.interval-minutes} * 60 * 1000}")
    public void cleanUpExpiredUrls() {
        try {
            log.debug("Running scheduled clean up of expired URLs");
            urlShortenerService.cleanUpExpiredUrls();
        } catch (Exception e) {
            log.warn("Failed to clean up expired URLs: {}", e.getMessage());
        }
    }
}
