package ning.linkverse.observability.web;

import ning.linkverse.core.request.RequestContextKeys;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RequestIdProperties 控制请求 ID 头名称与允许长度，限制日志注入面。
 *
 * @author ning
 * @date 2026-08-19
 */
@ConfigurationProperties("linkverse.observability.request-id")
public final class RequestIdProperties {

    private String headerName = RequestContextKeys.REQUEST_ID_HEADER;
    private int maxLength = 64;

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        if (headerName == null || headerName.isBlank() || headerName.contains("\r") || headerName.contains("\n")) {
            throw new IllegalArgumentException("请求 ID 头名称不合法");
        }
        this.headerName = headerName;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        if (maxLength < 16 || maxLength > 64) {
            throw new IllegalArgumentException("请求 ID 最大长度必须在 16 到 64 之间");
        }
        this.maxLength = maxLength;
    }
}
