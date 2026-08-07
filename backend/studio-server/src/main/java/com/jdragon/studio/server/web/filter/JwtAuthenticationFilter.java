package com.jdragon.studio.server.web.filter;

import com.jdragon.studio.infra.service.JwtTokenService;
import com.jdragon.studio.infra.service.StudioUserDetailsService;
import com.jdragon.studio.infra.security.StudioTokenResolver.ResolvedStudioToken;
import com.jdragon.studio.server.web.security.StudioHttpTokenResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final StudioUserDetailsService userDetailsService;
    private final StudioHttpTokenResolver tokenResolver;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService,
                                   StudioUserDetailsService userDetailsService,
                                   StudioHttpTokenResolver tokenResolver) {
        this.jwtTokenService = jwtTokenService;
        this.userDetailsService = userDetailsService;
        this.tokenResolver = tokenResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ResolvedStudioToken resolvedToken = tokenResolver.resolve(request);
        if (resolvedToken != null) {
            String token = resolvedToken.getToken();
            if (jwtTokenService.isValid(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
                String username = jwtTokenService.parseUsername(token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}

