package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.auth.AuthProfileView;
import com.jdragon.studio.dto.model.request.LoginRequest;
import com.jdragon.studio.dto.model.request.UserRegistrationRequestCreateRequest;
import com.jdragon.studio.dto.model.system.UserRegistrationRequestView;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import com.jdragon.studio.infra.service.GatewayAuthExchangeService;
import com.jdragon.studio.infra.service.JwtTokenService;
import com.jdragon.studio.infra.service.StudioAccessService;
import com.jdragon.studio.infra.service.UserRegistrationRequestService;
import com.jdragon.studio.server.web.security.StudioAuthCookieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Tag(name = "Auth", description = "Authentication APIs")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final StudioAccessService studioAccessService;
    private final UserRegistrationRequestService userRegistrationRequestService;
    private final GatewayAuthExchangeService gatewayAuthExchangeService;
    private final StudioAuthCookieService authCookieService;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenService jwtTokenService,
                          StudioAccessService studioAccessService,
                          UserRegistrationRequestService userRegistrationRequestService,
                          GatewayAuthExchangeService gatewayAuthExchangeService,
                          StudioAuthCookieService authCookieService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
        this.studioAccessService = studioAccessService;
        this.userRegistrationRequestService = userRegistrationRequestService;
        this.gatewayAuthExchangeService = gatewayAuthExchangeService;
        this.authCookieService = authCookieService;
    }

    @Operation(summary = "Login and get JWT token")
    @PostMapping("/login")
    public Result<AuthProfileView> login(@Valid @RequestBody LoginRequest request,
                                         HttpServletRequest servletRequest,
                                         HttpServletResponse servletResponse) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        String token = jwtTokenService.createToken(authentication.getName());
        StudioUserPrincipal principal = authentication.getPrincipal() instanceof StudioUserPrincipal
                ? (StudioUserPrincipal) authentication.getPrincipal()
                : null;
        AuthProfileView profile = studioAccessService.buildProfile(
                principal,
                servletRequest.getHeader(StudioConstants.REQUEST_TENANT_HEADER),
                servletRequest.getHeader(StudioConstants.REQUEST_PROJECT_HEADER),
                token);
        authCookieService.writeTokenCookie(servletRequest, servletResponse, token);
        return Result.success(profile);
    }

    @Operation(summary = "Get current login user")
    @GetMapping("/me")
    public Result<AuthProfileView> me(Authentication authentication,
                                      HttpServletRequest servletRequest) {
        StudioUserPrincipal principal = authentication != null && authentication.getPrincipal() instanceof StudioUserPrincipal
                ? (StudioUserPrincipal) authentication.getPrincipal()
                : null;
        return Result.success(studioAccessService.buildProfile(
                principal,
                servletRequest.getHeader(StudioConstants.REQUEST_TENANT_HEADER),
                servletRequest.getHeader(StudioConstants.REQUEST_PROJECT_HEADER),
                null));
    }

    @Operation(summary = "Exchange trusted gateway identity for studio JWT")
    @PostMapping("/gateway/exchange")
    public Result<AuthProfileView> gatewayExchange(HttpServletRequest servletRequest,
                                                   HttpServletResponse servletResponse) {
        AuthProfileView profile = gatewayAuthExchangeService.exchange(
                servletRequest.getHeader(StudioConstants.GATEWAY_USER_INFO_HEADER),
                servletRequest.getHeader(StudioConstants.GATEWAY_TIMESTAMP_HEADER),
                servletRequest.getHeader(StudioConstants.GATEWAY_REQUEST_PATH_HEADER),
                servletRequest.getHeader(StudioConstants.GATEWAY_SIGNATURE_HEADER),
                servletRequest.getHeader(StudioConstants.REQUEST_TENANT_HEADER),
                servletRequest.getHeader(StudioConstants.REQUEST_PROJECT_HEADER));
        authCookieService.writeTokenCookie(servletRequest, servletResponse, profile.getToken());
        return Result.success(profile);
    }

    @Operation(summary = "Clear the Studio browser session")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest servletRequest,
                               HttpServletResponse servletResponse) {
        authCookieService.clearTokenCookie(servletRequest, servletResponse);
        return Result.success(null);
    }

    @Operation(summary = "Submit registration request")
    @PostMapping("/register-requests")
    public Result<UserRegistrationRequestView> submitRegistrationRequest(@Valid @RequestBody UserRegistrationRequestCreateRequest request) {
        return Result.success(userRegistrationRequestService.submit(request));
    }
}

