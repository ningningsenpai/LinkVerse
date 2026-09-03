"""领域适配器注册入口。"""

from linkverse_recommendation.domains.base import DomainAdapter
from linkverse_recommendation.domains.trade import TradeDomainAdapter


def get_adapter(domain: str) -> DomainAdapter:
    """按稳定领域名返回适配器；未知领域必须显式失败。"""

    if domain == "trade":
        return TradeDomainAdapter()
    raise ValueError(f"未注册的推荐领域：{domain}")


__all__ = ["DomainAdapter", "TradeDomainAdapter", "get_adapter"]
