package in.ybuilds.shortify.service;

import in.ybuilds.shortify.dto.ShortenUrlRequest;
import in.ybuilds.shortify.dto.ShortenUrlResponse;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;

@Service
public class UrlShortenerService {
    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String clientIp) {
        return null;
    }
}
