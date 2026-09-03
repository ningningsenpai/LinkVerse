"""带来源标识且不会命中用户正样本的负采样。"""

from __future__ import annotations

import random
from collections.abc import Collection, Iterable, Mapping


def sample_negatives(
    positives_by_user: Mapping[str, Collection[str]],
    all_object_ids: Iterable[str],
    count_per_positive: int,
    seed: int,
) -> list[dict[str, object]]:
    """从用户从未产生正行为的对象中确定性采样，并显式标记 ``SAMPLED``。"""

    if count_per_positive < 1:
        raise ValueError("每个正样本的负采样数必须大于零")
    universe = sorted(set(all_object_ids))
    generator = random.Random(seed)
    sampled: list[dict[str, object]] = []
    for user_key in sorted(positives_by_user):
        positives = set(positives_by_user[user_key])
        candidates = [object_id for object_id in universe if object_id not in positives]
        required = len(positives) * count_per_positive
        if not candidates and required:
            raise ValueError(f"用户 {user_key} 没有可用负样本")
        for index in range(required):
            sampled.append(
                {
                    "user_key": user_key,
                    "object_id": candidates[generator.randrange(len(candidates))],
                    "label": 0,
                    "negative_source": "SAMPLED",
                    "sample_index": index,
                }
            )
    return sampled
