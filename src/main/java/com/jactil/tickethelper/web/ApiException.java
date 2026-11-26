package com.jactil.tickethelper.web;

/** 业务异常：由 {@link App} 统一渲染成 {"error": "..."}。 */
public class ApiException extends RuntimeException {

    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException serverError(String message) {
        return new ApiException(500, message);
    }
}
