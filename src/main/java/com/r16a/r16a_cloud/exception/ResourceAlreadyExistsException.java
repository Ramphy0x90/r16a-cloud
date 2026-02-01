package com.r16a.r16a_cloud.exception;

import lombok.Getter;

@Getter
public class ResourceAlreadyExistsException extends RuntimeException {

    private final String resource;
    private final String field;
    private final Object value;

    public ResourceAlreadyExistsException(String resource, String field, Object value) {
        super("%s already exists with %s: %s".formatted(resource, field, value));
        this.resource = resource;
        this.field = field;
        this.value = value;
    }
}
