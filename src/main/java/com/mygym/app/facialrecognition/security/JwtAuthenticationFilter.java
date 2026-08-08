package com.mygym.app.facialrecognition.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String requestURI = request.getRequestURI();
        LOGGER.info("[JWT FILTER] Intercepting request for URI: {}", requestURI);
        
        final String authHeader = request.getHeader("Authorization");
        LOGGER.info("[JWT FILTER] Authorization Header Present: {}", (authHeader != null));

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            LOGGER.warn("[JWT FILTER] No valid Bearer token header structure found. Passing to next filter chain channel.");
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);
        LOGGER.info("[JWT FILTER] Raw Token Length extracted: {} characters", jwt.length());
        
        try {
            final String username = jwtUtil.extractUsername(jwt);
            LOGGER.info("[JWT FILTER] Successfully extracted username from token payload: '{}'", username);

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                boolean isValid = jwtUtil.validateToken(jwt, username);
                LOGGER.info("[JWT FILTER] Token structural validity check result: {}", isValid);

                if (isValid) {
                    String role = jwtUtil.extractAllClaims(jwt).get("role", String.class);
                    LOGGER.info("[JWT FILTER] Extracted user role payload claim: '{}'", role);
                    
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    LOGGER.info("[JWT FILTER] Successfully set SecurityContextHolder Authentication for user: '{}'", username);
                } else {
                    LOGGER.warn("[JWT FILTER] Token validation check failed for username: '{}'", username);
                }
            }
            filterChain.doFilter(request, response);
            
        } catch (Exception e) {
            LOGGER.error("[JWT FILTER CRASH] Exception intercepted during signature processing! Error Type: {}, Message: {}", 
                      e.getClass().getName(), e.getMessage(), e);
            
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Invalid, expired, or malformed authentication token structure. System Log Context: " + e.getMessage() + "\"}");
        }
    }
}
