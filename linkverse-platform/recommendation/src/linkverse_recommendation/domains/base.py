"""推荐领域适配器接口。"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Iterable, Mapping

from linkverse_recommendation.core.events import BehaviorEvent


class DomainAdapter(ABC):
    """隔离领域对象、标签和上线门槛，公共流水线只依赖本接口。"""

    @property
    @abstractmethod
    def name(self) -> str:
        """返回稳定的小写领域名。"""

    @abstractmethod
    def normalize_object(self, raw: Mapping[str, object]) -> dict[str, object]:
        """把领域对象转换为公共对象字段和领域扩展字段。"""

    @abstractmethod
    def label_gain(self, events: Iterable[BehaviorEvent]) -> int:
        """按同一会话用户—对象的最高有效行为生成排序增益。"""

    @abstractmethod
    def deep_model_ready(self, events: Iterable[BehaviorEvent]) -> bool:
        """判断是否达到启用图模型或深度多任务模型的数据门槛。"""
