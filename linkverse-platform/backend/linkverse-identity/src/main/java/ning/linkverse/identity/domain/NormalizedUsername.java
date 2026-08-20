package ning.linkverse.identity.domain;

/**
 * NormalizedUsername 同时保存展示值与执行唯一匹配的规范化值。
 *
 * @author ning
 * @date 2026-08-19
 */
public record NormalizedUsername(String displayValue, String lookupValue) {
}
