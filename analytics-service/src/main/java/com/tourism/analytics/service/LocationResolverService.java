package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.feign.LocationSyncDTO;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Resolves user-mentioned places from real catalog/vector data.
 *
 * This intentionally avoids hard-coded destination lists. Direct matching uses
 * tour-catalog locations; semantic fallback uses Pinecone documents and their
 * metadata.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocationResolverService {

    public enum Role { ANY, START, DESTINATION }

    public record ResolvedLocation(String name, Integer id, Role role, String source) {}

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final TourCatalogFeignClient tourCatalogClient;
    private final VectorService vectorService;
    private final Gson gson = new Gson();

    private volatile List<LocationCandidate> cachedLocations = List.of();
    private volatile Instant cacheLoadedAt = Instant.EPOCH;

    public Optional<ResolvedLocation> resolve(String text, Role role) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) return Optional.empty();

        Optional<ResolvedLocation> fromCatalog = resolveFromCatalog(normalizedText, role);
        if (fromCatalog.isPresent()) return fromCatalog;

        if (shouldSkipVectorFallback(normalizedText)) {
            return Optional.empty();
        }

        return resolveFromVectors(text, normalizedText, role);
    }

    public String normalizeText(String text) {
        return normalize(text);
    }

    private Optional<ResolvedLocation> resolveFromCatalog(String normalizedText, Role role) {
        for (LocationCandidate candidate : loadLocations()) {
            for (String matchKey : candidate.matchKeys()) {
                if (!matchKey.isBlank() && containsTokenized(normalizedText, matchKey)) {
                    return Optional.of(new ResolvedLocation(candidate.name(), candidate.id(), role, "tour-catalog"));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedLocation> resolveFromVectors(String originalText, String normalizedText, Role role) {
        try {
            List<VectorDocumentDTO> docs = vectorService.searchSimilar(originalText, 12);
            for (VectorDocumentDTO doc : docs) {
                Map<String, Object> meta = readMetadata(doc);
                if (meta.isEmpty()) continue;

                if (role == Role.START || role == Role.ANY) {
                    Optional<ResolvedLocation> start = fromMeta(meta, "startLocationName", "startLocationID", Role.START, normalizedText);
                    if (start.isPresent()) return start;
                }
                if (role == Role.DESTINATION || role == Role.ANY) {
                    Optional<ResolvedLocation> end = fromMeta(meta, "endLocationName", "endLocationID", Role.DESTINATION, normalizedText);
                    if (end.isPresent()) return end;
                    Optional<ResolvedLocation> loc = fromMeta(meta, "name", "locationID", Role.DESTINATION, normalizedText);
                    if (loc.isPresent()) return loc;
                }
            }
        } catch (Exception e) {
            log.debug("Location vector fallback failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private boolean shouldSkipVectorFallback(String normalizedText) {
        String[] tokens = normalizedText.split("\\s+");
        boolean hasRouteContext = normalizedText.matches(".*\\b(den|di|tu|khoi\\s*hanh|xuat\\s*phat|toi\\s*o|minh\\s*o)\\b.*");
        return tokens.length <= 2 && !hasRouteContext;
    }

    private Optional<ResolvedLocation> fromMeta(Map<String, Object> meta, String nameKey, String idKey, Role role, String normalizedText) {
        String name = stringValue(meta.get(nameKey));
        if (name.isBlank()) return Optional.empty();

        String normalizedName = normalize(name);
        String compactName = normalizedName.replace(" ", "");
        String acronym = acronym(normalizedName);

        boolean direct = containsTokenized(normalizedText, normalizedName)
                || (!compactName.isBlank() && normalizedText.replace(" ", "").contains(compactName))
                || (acronym.length() >= 3 && containsTokenized(normalizedText, acronym));

        if (!direct) return Optional.empty();

        Integer id = intValue(meta.get(idKey));
        return Optional.of(new ResolvedLocation(name, id, role, "pinecone"));
    }

    private List<LocationCandidate> loadLocations() {
        Instant now = Instant.now();
        if (!cachedLocations.isEmpty() && Duration.between(cacheLoadedAt, now).compareTo(CACHE_TTL) < 0) {
            return cachedLocations;
        }

        try {
            List<LocationSyncDTO> locations = tourCatalogClient.getLocationsForChatbotSync();
            List<LocationCandidate> candidates = locations == null ? List.of() : locations.stream()
                    .filter(l -> l.getName() != null && !l.getName().isBlank())
                    .map(this::toCandidate)
                    .toList();
            cachedLocations = candidates;
            cacheLoadedAt = now;
            return candidates;
        } catch (Exception e) {
            log.warn("Cannot load chatbot locations from catalog: {}", e.getMessage());
            return cachedLocations;
        }
    }

    private LocationCandidate toCandidate(LocationSyncDTO location) {
        Set<String> matchKeys = new LinkedHashSet<>();
        addMatchKey(matchKeys, location.getName());
        addMatchKey(matchKeys, location.getAirportCode());
        addMatchKey(matchKeys, location.getAirportName());

        String normalizedName = normalize(location.getName());
        addMatchKey(matchKeys, normalizedName.replace(" ", ""));
        String acronym = acronym(normalizedName);
        if (acronym.length() >= 3) {
            addMatchKey(matchKeys, acronym);
        }

        return new LocationCandidate(location.getLocationID(), location.getName(), matchKeys);
    }

    private void addMatchKey(Set<String> matchKeys, String raw) {
        String normalized = normalize(raw);
        if (!normalized.isBlank()) matchKeys.add(normalized);
    }

    private boolean containsTokenized(String text, String value) {
        if (value.isBlank()) return false;
        // 1. Exact token boundary match (original behaviour)
        if ((" " + text + " ").contains(" " + value + " ")) return true;
        // 2. Fuzzy match: split value into tokens, try each token against text tokens
        //    Allow Levenshtein distance ≤ 2 for tokens with length ≥ 4
        String[] valueTokens = value.split("\\s+");
        String[] textTokens  = text.split("\\s+");
        for (String vt : valueTokens) {
            if (vt.length() < 4) continue; // skip very short tokens to avoid false positives
            for (String tt : textTokens) {
                if (tt.length() < 4) continue;
                int dist = levenshtein(vt, tt);
                int maxAllowed = vt.length() <= 5 ? 1 : 2; // stricter for short tokens
                if (dist <= maxAllowed) return true;
            }
        }
        return false;
    }

    /**
     * Levenshtein distance — pure Java, no external libs.
     * Used for typo-tolerant location matching (e.g. "vũng tù" → "vũng tàu").
     */
    static int levenshtein(String a, String b) {
        int la = a.length(), lb = b.length();
        int[] prev = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            int[] curr = new int[lb + 1];
            curr[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[lb];
    }

    private Map<String, Object> readMetadata(VectorDocumentDTO doc) {
        try {
            if (doc.getMetadata() == null || doc.getMetadata().isBlank()) return Map.of();
            return gson.fromJson(doc.getMetadata(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9/\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String acronym(String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) return "";
        Set<String> ignored = Set.of("tp", "thanh", "pho", "tinh", "quan", "huyen", "thi", "xa");
        StringBuilder sb = new StringBuilder();
        for (String token : normalizedName.split("\\s+")) {
            if (!token.isBlank() && !ignored.contains(token)) sb.append(token.charAt(0));
        }
        return sb.toString();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer intValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private record LocationCandidate(Integer id, String name, Set<String> matchKeys) {}
}
