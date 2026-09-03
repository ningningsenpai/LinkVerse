import json

import pytest

from linkverse_recommendation.training.vocabulary import build_vocabulary, encode, load_vocabulary


def test_vocabulary_reserves_zero_for_oov_and_is_stable():
    vocabulary = build_vocabulary(["b", "a", "b"])

    assert vocabulary == {"a": 1, "b": 2}
    assert encode("missing", vocabulary) == 0


def test_loading_vocabulary_rejects_embedding_misalignment(tmp_path):
    path = tmp_path / "vocabulary.json"
    path.write_text(json.dumps({"a": 2}), encoding="utf-8")

    with pytest.raises(ValueError, match="OOV"):
        load_vocabulary(path)
