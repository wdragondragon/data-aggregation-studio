package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@Schema(description = "Login request")
public class LoginRequest {
    @NotBlank(message = "Username is required")
    @Schema(description = "Login username", required = true, example = "admin")
    private String username;

    @NotBlank(message = "Password is required")
    @Schema(description = "Login password", required = true, example = "admin123")
    private String password;
}
