package com.tourism.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
@Component
public class AuthHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
       return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(jwtAuth -> {
                    Jwt jwt = (Jwt) jwtAuth.getToken();

                    //Extract claims from Keycloak JWT
                    String userId = jwt.getClaimAsString("userId");
                    String email = jwt.getClaimAsString("email");
                    String role = extractRole(jwt);    
                    
                    ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id",    userId != null ? userId : "")
                            .header("X-User-Email", email != null ? email : "")
                            .header("X-User-Role",  role != null ? role : "")
                            .build();
                    return exchange.mutate().request(modifiedRequest).build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @SuppressWarnings("unchecked")
    private String extractRole(Jwt jwt) {
        // Lấy role đầu tiên trong realm_access.roles (CUSTOMER / ADMIN / TOUR_OWNER)
        var realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return null;
        
        var roles = (java.util.List<String>) realmAccess.get("roles");
        if(roles == null || roles.isEmpty()) return null;

        // Bỏ qua các role mặc định của Keycloak

         return roles.stream()
                .filter(r -> r.equals("CUSTOMER") || r.equals("ADMIN") || r.equals("TOUR_OWNER"))
                .findFirst()
                .orElse(null);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
