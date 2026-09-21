package in.ybuilds.shortify.controller;

import in.ybuilds.shortify.dto.ShortenUrlRequest;
import in.ybuilds.shortify.dto.ShortenUrlResponse;
import in.ybuilds.shortify.service.RateLimitService;
import in.ybuilds.shortify.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class UrlShortenerController {
    private final UrlShortenerService urlShortenerService;
    private final RateLimitService rateLimitService;

    @PostMapping("/shorten")
    public ResponseEntity<?> shortenUrl(
            @Valid @RequestBody ShortenUrlRequest request,
            HttpServletRequest httpServletRequest
    ) {
        String clientIp = getClientIp(httpServletRequest);

        if (!rateLimitService.isAllowed(clientIp)) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "rate limit exceeded",
                            "remainingRequests", rateLimitService.getRemainingRequests(clientIp),
                            "timeUntilReset", rateLimitService.getTimeUntilReset(clientIp)
                    ));
        }

        try {
            ShortenUrlResponse response = urlShortenerService.shortenUrl(request, clientIp);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
        }
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<?> redirectToUrl(
            @PathVariable String shortCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String clientIp = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        String referrer = request.getHeader("Referrer");

        Optional<String> originalUrl = urlShortenerService.getOriginalUrl(shortCode);

        if(originalUrl.isPresent()) {
            urlShortenerService.recordClick(shortCode, clientIp, userAgent, referrer);
            response.setHeader("Location", originalUrl.get());
            return ResponseEntity.status(HttpStatus.FOUND).build();
        } else {
            log.warn("Requested short code's original IP not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    private String getClientIp(HttpServletRequest httpServletRequest) {
        String xForwardedFor = httpServletRequest.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0];
        }

        String xRealIp = httpServletRequest.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return httpServletRequest.getRemoteAddr();
    }
}
