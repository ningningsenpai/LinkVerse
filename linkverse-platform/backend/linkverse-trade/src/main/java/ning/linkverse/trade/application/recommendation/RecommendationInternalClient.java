package ning.linkverse.trade.application.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * RecommendationInternalClient 使用服务 JWT、800ms 超时和熔断器访问 Python 服务。
 *
 * @author ning
 * @date 2026-09-03
 */
@Component
public class RecommendationInternalClient {

    private final RestClient restClient;
    private final RecommendationServiceTokenProvider tokenProvider;
    private final CircuitBreaker circuitBreaker;
    private final Semaphore concurrentCalls;

    public RecommendationInternalClient(
            RecommendationServiceTokenProvider tokenProvider,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Value("${linkverse.trade.recommendation-base-url:http://linkverse-recommendation}") String baseUrl,
            @Value("${linkverse.trade.recommendation-timeout:800ms}") Duration timeout,
            @Value("${linkverse.trade.recommendation-max-concurrent:20}") int maxConcurrent
    ) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
        this.circuitBreaker = circuitBreakerFactory.create("recommendation");
        this.concurrentCalls = new Semaphore(maxConcurrent);
    }

    public RecommendationInternalResponse recommend(RecommendationInternalRequest request) {
        if (!concurrentCalls.tryAcquire()) {
            throw new java.util.concurrent.RejectedExecutionException("推荐服务并发已达上限");
        }
        try {
            return circuitBreaker.run(() -> restClient.post()
                    .uri("/internal/v1/recommendations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getAccessToken())
                    .body(request)
                    .retrieve()
                    .body(RecommendationInternalResponse.class));
        } finally {
            concurrentCalls.release();
        }
    }
}
