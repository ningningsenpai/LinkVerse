package ning.linkverse.trade.application.recommendation;

import ning.linkverse.trade.domain.listing.BookListing;
import ning.linkverse.trade.domain.recommendation.RecommendationCandidate;
import ning.linkverse.trade.domain.recommendation.RecommendationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RecommendationApplicationServiceTest 验证远程候选过滤、数据库补位和故障降级。
 *
 * @author ning
 * @date 2026-09-03
 */
class RecommendationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T06:00:00Z");

    @Test
    void shouldPreserveRemoteOrderAfterAuthoritativeFilterAndFillFromDatabase() {
        RecommendationInternalClient client = mock(RecommendationInternalClient.class);
        RecommendationRepository repository = mock(RecommendationRepository.class);
        RecommendationDeliveryService deliveryService = mock(RecommendationDeliveryService.class);
        UserKeyHasher hasher = mock(UserKeyHasher.class);
        BookListing eligible = listing(1L);
        BookListing fallback = listing(3L);
        when(hasher.hash(1001L)).thenReturn("a".repeat(64));
        when(client.recommend(any())).thenAnswer(invocation -> {
            RecommendationInternalRequest request = invocation.getArgument(0);
            return new RecommendationInternalResponse(
                    request.requestId(),
                    "trade",
                    "trade-model-v1",
                    List.of(candidate(2L), candidate(1L))
            );
        });
        when(repository.findEligibleListings(eq(1001L), anyCollection(), eq(NOW)))
                .thenReturn(List.of(eligible));
        when(repository.findFallbackListings(eq(1001L), anyCollection(), eq(NOW), eq(1)))
                .thenReturn(List.of(fallback));
        when(deliveryService.persist(anyLong(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(List.of());
        RecommendationApplicationService service = service(client, repository, deliveryService, hasher);

        RecommendationResult result = service.recommend(1001L, "HOME", 2);

        assertThat(result.modelVersion()).isEqualTo("trade-model-v1");
        assertThat(result.fallback()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookListing>> listings = ArgumentCaptor.forClass(List.class);
        verify(deliveryService).persist(
                eq(1001L), any(), eq("trade-model-v1"), eq("HOME"), listings.capture(), anyMap(), eq(NOW)
        );
        assertThat(listings.getValue()).extracting(BookListing::id).containsExactly(1L, 3L);
    }

    @Test
    void shouldUseDatabaseFallbackWhenRecommendationServiceFails() {
        RecommendationInternalClient client = mock(RecommendationInternalClient.class);
        RecommendationRepository repository = mock(RecommendationRepository.class);
        RecommendationDeliveryService deliveryService = mock(RecommendationDeliveryService.class);
        UserKeyHasher hasher = mock(UserKeyHasher.class);
        when(hasher.hash(1001L)).thenReturn("a".repeat(64));
        when(client.recommend(any())).thenThrow(new IllegalStateException("服务不可用"));
        when(repository.findEligibleListings(eq(1001L), anyCollection(), eq(NOW)))
                .thenReturn(List.of());
        when(repository.findFallbackListings(eq(1001L), anyCollection(), eq(NOW), eq(1)))
                .thenReturn(List.of(listing(1L)));
        when(deliveryService.persist(anyLong(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(List.of());
        RecommendationApplicationService service = service(client, repository, deliveryService, hasher);

        RecommendationResult result = service.recommend(1001L, "HOME", 1);

        assertThat(result.modelVersion()).isEqualTo("trade-fallback-v1");
        assertThat(result.fallback()).isTrue();
    }

    @Test
    void shouldRejectUnknownSceneBeforeCallingDependencies() {
        RecommendationApplicationService service = service(
                mock(RecommendationInternalClient.class),
                mock(RecommendationRepository.class),
                mock(RecommendationDeliveryService.class),
                mock(UserKeyHasher.class)
        );

        assertThatThrownBy(() -> service.recommend(1001L, "UNKNOWN", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("推荐场景或数量不合法");
    }

    private static RecommendationApplicationService service(
            RecommendationInternalClient client,
            RecommendationRepository repository,
            RecommendationDeliveryService deliveryService,
            UserKeyHasher hasher
    ) {
        return new RecommendationApplicationService(
                client,
                repository,
                deliveryService,
                hasher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static RecommendationCandidate candidate(long listingId) {
        return new RecommendationCandidate(
                Long.toString(listingId),
                BigDecimal.ONE,
                List.of("ITEM_CF"),
                "SIMILAR_ITEM"
        );
    }

    private static BookListing listing(long listingId) {
        return new BookListing(
                listingId,
                9001L,
                1L,
                "UNCLASSIFIED",
                "测试商品",
                "测试作者",
                "测试描述",
                new BigDecimal("39.9000"),
                "CNY",
                "ON_SALE",
                NOW.minusSeconds(3600),
                0L,
                10,
                NOW
        );
    }
}
