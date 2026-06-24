package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JavaMemberHint {
    private String name;
    private String kind;
    private Boolean staticMember;
    private String returnType;
    private String declaringClass;
    private List<String> parameterTypes = new ArrayList<String>();
    private List<String> parameterNames = new ArrayList<String>();
    private String displaySignature;
    private String insertText;
}
