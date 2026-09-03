"""双塔对象向量的 CPU Faiss 精确召回。"""

from __future__ import annotations

from collections.abc import Sequence

from linkverse_recommendation.pipeline.recall.base import RecallHit


class FlatInnerProductRecall:
    """当前目录规模使用 IndexFlatIP，避免过早引入近似检索误差。"""

    def __init__(self, object_ids: Sequence[str], normalized_embeddings) -> None:
        import faiss
        import numpy as np

        matrix = np.asarray(normalized_embeddings, dtype="float32")
        if matrix.ndim != 2 or matrix.shape[0] != len(object_ids):
            raise ValueError("对象标识与向量数量不一致")
        self.object_ids = list(object_ids)
        self.index = faiss.IndexFlatIP(matrix.shape[1])
        self.index.add(matrix)

    def recall(self, normalized_user_embedding, limit: int = 100) -> list[RecallHit]:
        import numpy as np

        vector = np.asarray(normalized_user_embedding, dtype="float32").reshape(1, -1)
        scores, indexes = self.index.search(vector, min(limit, len(self.object_ids)))
        return [
            RecallHit(self.object_ids[int(index)], "TWO_TOWER", float(score), rank)
            for rank, (index, score) in enumerate(zip(indexes[0], scores[0], strict=True), 1)
            if index >= 0
        ]
