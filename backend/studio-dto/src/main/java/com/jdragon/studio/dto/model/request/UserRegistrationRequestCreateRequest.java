package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class UserRegistrationRequestCreateRequest {
    private String username;
    private String password;
    private String displayName;
    private String reason;
}
