package com.seckill.common;

/**
 * 统一 API 响应体——所有 Controller 返回此结构。
 *
 * @param <T> 业务数据的具体类型，无数据时为 {@code Void} 或 {@code Object}
 * @author TianJunQi
 * @since 2026-05-12
 */
public class ApiResponse<T> {

    /** 业务状态码，{@code 0} 表示成功 */
    private int code;
    /** 人类可读的描述信息 */
    private String message;
    /** 响应数据体，无数据时为 {@code null} */
    private T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功响应（带数据）。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return code=0 的响应体
     * @since 2026-05-12
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功响应（无数据）。
     *
     * @param <T> 数据类型
     * @return code=0、data=null 的响应体
     * @since 2026-05-12
     */
    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /**
     * 失败响应。
     *
     * @param resultCode 业务状态码枚举
     * @param <T>        数据类型
     * @return 包含状态码和消息的响应体
     * @since 2026-05-12
     */
    public static <T> ApiResponse<T> fail(ResultCode resultCode) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /**
     * 失败响应（带附加数据——如验证码问题）。
     *
     * @param resultCode 业务状态码枚举
     * @param data       附加数据
     * @param <T>        数据类型
     * @return 包含状态码、消息和附加数据的响应体
     * @since 2026-05-12
     */
    public static <T> ApiResponse<T> fail(ResultCode resultCode, T data) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMessage(), data);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
