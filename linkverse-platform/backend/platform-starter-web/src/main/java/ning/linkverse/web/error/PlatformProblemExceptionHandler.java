package ning.linkverse.web.error;

import ning.linkverse.core.error.CommonErrorCode;
import ning.linkverse.core.error.ErrorCode;
import ning.linkverse.core.error.PlatformException;
import ning.linkverse.core.request.RequestContextKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

/**
 * PlatformProblemExceptionHandler 将可预期异常转换为稳定的 application/problem+json 响应。
 *
 * @author ning
 * @date 2026-08-19
 */
@RestControllerAdvice
public class PlatformProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PlatformProblemExceptionHandler.class);

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ProblemDetail> handlePlatformException(
            PlatformException exception,
            HttpServletRequest request
    ) {
        return response(exception.errorCode(), exception.getMessage(), request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<ProblemDetail> handleValidationException(Exception exception, HttpServletRequest request) {
        return response(CommonErrorCode.INVALID_REQUEST, "请求参数校验失败", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(CommonErrorCode.INVALID_REQUEST, "请求体格式不正确", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
        return response(CommonErrorCode.NOT_FOUND, CommonErrorCode.NOT_FOUND.defaultMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        log.error(
                "出现未处理异常 error_code={} exception_type={}",
                CommonErrorCode.INTERNAL_ERROR.code(),
                exception.getClass().getName()
        );
        return response(
                CommonErrorCode.INTERNAL_ERROR,
                CommonErrorCode.INTERNAL_ERROR.defaultMessage(),
                request
        );
    }

    private ResponseEntity<ProblemDetail> response(
            ErrorCode errorCode,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(errorCode.status()),
                detail
        );
        problem.setType(URI.create("urn:linkverse:error:" + errorCode.code().toLowerCase()));
        problem.setTitle("请求处理失败");
        problem.setProperty("code", errorCode.code());
        problem.setProperty("request_id", request.getAttribute(RequestContextKeys.REQUEST_ID_ATTRIBUTE));
        problem.setProperty("trace_id", currentTraceId());
        return ResponseEntity
                .status(errorCode.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private String currentTraceId() {
        String traceId = MDC.get(RequestContextKeys.TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get("traceId");
        }
        return traceId;
    }
}
