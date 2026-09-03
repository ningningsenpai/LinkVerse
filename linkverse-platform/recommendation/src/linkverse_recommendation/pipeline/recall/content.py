"""TF-IDF 内容相似召回。"""

from __future__ import annotations

from collections.abc import Mapping, Sequence

from linkverse_recommendation.pipeline.recall.base import RecallHit


class TfidfContentRecall:
    """将标题、作者、描述和分类统一编码，并对用户历史向量求加权中心。"""

    def __init__(self) -> None:
        self.object_ids: list[str] = []
        self.index_by_id: dict[str, int] = {}
        self.matrix = None
        self.vectorizer = None

    def fit(self, objects: Sequence[Mapping[str, object]]) -> "TfidfContentRecall":
        from sklearn.feature_extraction.text import TfidfVectorizer

        self.object_ids = [str(row["object_id"]) for row in objects]
        self.index_by_id = {
            object_id: index for index, object_id in enumerate(self.object_ids)
        }
        documents = [
            " ".join(
                str(row.get(field, ""))
                for field in ("title", "author", "description", "category_code")
            )
            for row in objects
        ]
        self.vectorizer = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), min_df=1)
        self.matrix = self.vectorizer.fit_transform(documents)
        return self

    def recall(self, history: Sequence[tuple[str, float]], limit: int = 100) -> list[RecallHit]:
        import numpy as np

        if self.matrix is None:
            raise RuntimeError("TF-IDF 召回器尚未训练")
        valid = [
            (self.index_by_id[item], weight)
            for item, weight in history
            if item in self.index_by_id
        ]
        if not valid:
            return []
        profile = sum((self.matrix[index] * weight for index, weight in valid), start=self.matrix[valid[0][0]] * 0)
        scores = (self.matrix @ profile.T).toarray().ravel()
        seen = {item for item, _ in history}
        order = np.argsort(-scores)
        result: list[RecallHit] = []
        for index in order:
            object_id = self.object_ids[int(index)]
            if object_id in seen:
                continue
            result.append(RecallHit(object_id, "TFIDF", float(scores[index]), len(result) + 1))
            if len(result) == limit:
                break
        return result

    def recall_many(
        self,
        histories: Mapping[str, Sequence[tuple[str, float]]],
        limit: int = 100,
        batch_size: int = 256,
    ) -> dict[str, list[RecallHit]]:
        """批量构造用户内容画像，避免为每个用户重复扫描词表和创建稀疏算子。"""

        import numpy as np
        from scipy.sparse import csr_matrix

        if self.matrix is None:
            raise RuntimeError("TF-IDF 召回器尚未训练")
        user_keys = sorted(histories)
        row_indexes: list[int] = []
        column_indexes: list[int] = []
        weights: list[float] = []
        seen_indexes: list[set[int]] = []
        for row_index, user_key in enumerate(user_keys):
            seen: set[int] = set()
            for object_id, weight in histories[user_key]:
                item_index = self.index_by_id.get(object_id)
                if item_index is None:
                    continue
                row_indexes.append(row_index)
                column_indexes.append(item_index)
                weights.append(float(weight))
                seen.add(item_index)
            seen_indexes.append(seen)
        user_items = csr_matrix(
            (weights, (row_indexes, column_indexes)),
            shape=(len(user_keys), len(self.object_ids)),
            dtype="float32",
        )
        profiles = user_items @ self.matrix
        result: dict[str, list[RecallHit]] = {}
        for start in range(0, len(user_keys), batch_size):
            stop = min(start + batch_size, len(user_keys))
            scores = (profiles[start:stop] @ self.matrix.T).toarray()
            for offset, values in enumerate(scores):
                user_index = start + offset
                seen = seen_indexes[user_index]
                if seen:
                    values[list(seen)] = -np.inf
                available = len(self.object_ids) - len(seen)
                count = min(limit, available)
                if count <= 0:
                    result[user_keys[user_index]] = []
                    continue
                indexes = np.argpartition(-values, count - 1)[:count]
                ordered = sorted(
                    (int(index) for index in indexes),
                    key=lambda index: (-float(values[index]), self.object_ids[index]),
                )
                result[user_keys[user_index]] = [
                    RecallHit(
                        self.object_ids[index],
                        "TFIDF",
                        float(values[index]),
                        rank,
                    )
                    for rank, index in enumerate(ordered, 1)
                ]
        return result
