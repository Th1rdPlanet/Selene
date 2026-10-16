package ru.moonlightproject.dev.selene.paper;

import java.net.URI;
import java.util.HexFormat;
import java.util.UUID;

record ResourcePackDelivery(UUID id, String url, byte[] sha1, String prompt) {
    static ResourcePackDelivery parse(String id, String url, String sha1, String prompt) {
        UUID parsedId;
        try {
            parsedId = UUID.fromString(id);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("captcha.resource-pack.id must be a UUID", error);
        }
        URI parsedUrl;
        try {
            parsedUrl = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("captcha.resource-pack.url must be a valid HTTP(S) URL", error);
        }
        if (!("http".equalsIgnoreCase(parsedUrl.getScheme()) || "https".equalsIgnoreCase(parsedUrl.getScheme())) || parsedUrl.getHost() == null) {
            throw new IllegalArgumentException("captcha.resource-pack.url must be a valid HTTP(S) URL");
        }
        byte[] parsedHash;
        try {
            parsedHash = HexFormat.of().parseHex(sha1);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("captcha.resource-pack.sha1 must contain 40 hexadecimal characters", error);
        }
        if (parsedHash.length != 20) throw new IllegalArgumentException("captcha.resource-pack.sha1 must contain 40 hexadecimal characters");
        return new ResourcePackDelivery(parsedId, url, parsedHash, prompt == null ? "" : prompt);
    }
}
