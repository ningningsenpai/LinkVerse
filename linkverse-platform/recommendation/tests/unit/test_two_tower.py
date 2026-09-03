import pytest


torch = pytest.importorskip("torch")

from linkverse_recommendation.training.two_tower import TwoTowerConfig, create_model, load_checkpoint, save_checkpoint


def test_two_tower_registers_and_restores_all_parameters(tmp_path):
    config = TwoTowerConfig(user_count=3, object_count=5, embedding_dim=4, hidden_dims=(8, 4))
    model = create_model(config)
    path = tmp_path / "model.pt"

    save_checkpoint(path, model, config)
    restored, restored_config = load_checkpoint(path)

    assert restored_config == config
    assert set(restored.state_dict()) == set(model.state_dict())
    assert any(name.startswith("user_tower") for name in restored.state_dict())
    assert any(name.startswith("object_tower") for name in restored.state_dict())


def test_two_tower_forward_returns_logits_for_positive_and_negatives():
    model = create_model(TwoTowerConfig(2, 4, embedding_dim=4, hidden_dims=(4,)))

    logits = model(torch.tensor([1, 2]), torch.tensor([1, 2]), torch.tensor([[2, 3], [3, 4]]))

    assert logits.shape == (2, 3)
    assert logits.requires_grad
