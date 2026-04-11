package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.auth.AuthProfileView;
import com.jdragon.studio.dto.model.request.LoginRequest;
import com.jdragon.studio.dto.model.request.UserRegistrationRequestCreateRequest;
import com.jdragon.studio.dto.model.system.UserRegistrationRequestView;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import com.jdragon.studio.infra.service.JwtTokenService;
import com.jdragon.studio.infra.service.StudioAccessService;
import com.jdragon.studio.infra.service.UserRegistrationRequestService;
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

import javax.validation.Valid;
import javax.servlet.http.HttpServletRequest;

@Tag(name = "Auth", description = "Authentication APIs")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final StudioAccessService studioAccessService;
    private final UserRegistrationRequestService userRegistrationRequestService;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenService jwtTokenService,
                          StudioAccessService studioAccessService,
                          UserRegistrationRequestService userRegistrationRequestService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
        this.studioAccessService = studioAccessService;
        this.userRegistrationRequestService = userRegistrationRequestService;
    }

    @Operation(summary = "Login and get JWT token")
    @PostMapping("/login")
    public Result<AuthProfileView> login(@Valid @RequestBody LoginRequest request,
                                         HttpServletRequest servletRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        String token = jwtTokenService.createToken(authentication.getName());
        StudioUserPrincipal principal = authentication.getPrincipal() instanceof StudioUserPrincipal
                ? (StudioUserPrincipal) authentication.getPrincipal()
                : null;
        return Result.success(studioAccessService.buildProfile(
                principal,
                servletRequest.getHeader(StudioConstants.REQUEST_TENANT_HEADER),
                servletRequest.getHeader(StudioConstants.REQUEST_PROJECT_HEADER),
                token));
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

    @Operation(summary = "Submit registration request")
    @PostMapping("/register-requests")
    public Result<UserRegistrationRequestView> submitRegistrationRequest(@Valid @RequestBody UserRegistrationRequestCreateRequest request) {
        return Result.success(userRegistrationRequestService.submit(request));
    }
}
