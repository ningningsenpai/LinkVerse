"""使用 logits 训练且可完整保存恢复的双塔模型。"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class TwoTowerConfig:
    """双塔结构和对比学习超参数。"""

    user_count: int
    object_count: int
    embedding_dim: int = 64
    hidden_dims: tuple[int, ...] = (128, 64)
    dropout: float = 0.1
    temperature: float = 0.1


def create_model(config: TwoTowerConfig):
    """延迟导入 PyTorch，使数据审计命令无需加载 CUDA 运行时。"""

    import torch
    from torch import nn

    class TwoTowerModel(nn.Module):
        """用户塔和对象塔均注册为子模块，杜绝任务头遗漏保存。"""

        def __init__(self) -> None:
            super().__init__()
            self.user_embedding = nn.Embedding(config.user_count + 1, config.embedding_dim, padding_idx=0)
            self.object_embedding = nn.Embedding(config.object_count + 1, config.embedding_dim, padding_idx=0)
            self.user_tower = _tower(nn, config)
            self.object_tower = _tower(nn, config)
            self.temperature = config.temperature

        def encode_users(self, user_ids):
            return nn.functional.normalize(self.user_tower(self.user_embedding(user_ids)), dim=-1)

        def encode_objects(self, object_ids):
            return nn.functional.normalize(self.object_tower(self.object_embedding(object_ids)), dim=-1)

        def forward(self, user_ids, positive_ids, negative_ids):
            users = self.encode_users(user_ids)
            positives = self.encode_objects(positive_ids).unsqueeze(1)
            negatives = self.encode_objects(negative_ids)
            objects = torch.cat((positives, negatives), dim=1)
            # CrossEntropyLoss 直接接收未经 Sigmoid 的相似度 logits。
            return torch.einsum("bd,bnd->bn", users, objects) / self.temperature

    return TwoTowerModel()


def _tower(nn, config: TwoTowerConfig):
    layers: list[object] = []
    current = config.embedding_dim
    for hidden in config.hidden_dims:
        layers.extend((nn.Linear(current, hidden), nn.ReLU(), nn.Dropout(config.dropout)))
        current = hidden
    if current != config.embedding_dim:
        layers.append(nn.Linear(current, config.embedding_dim))
    return nn.Sequential(*layers)


def train_epoch(model, loader, optimizer, scaler, device: str) -> float:
    """使用 AMP 训练一个 epoch，目标类别始终是索引 0 的正对象。"""

    import torch

    model.train()
    total_loss = 0.0
    batches = 0
    criterion = torch.nn.CrossEntropyLoss()
    for user_ids, positive_ids, negative_ids in loader:
        user_ids = user_ids.to(device, non_blocking=True)
        positive_ids = positive_ids.to(device, non_blocking=True)
        negative_ids = negative_ids.to(device, non_blocking=True)
        optimizer.zero_grad(set_to_none=True)
        with torch.autocast(device_type="cuda", dtype=torch.float16):
            logits = model(user_ids, positive_ids, negative_ids)
            targets = torch.zeros(logits.shape[0], dtype=torch.long, device=device)
            loss = criterion(logits, targets)
        scaler.scale(loss).backward()
        scaler.step(optimizer)
        scaler.update()
        total_loss += float(loss.detach())
        batches += 1
    return total_loss / max(1, batches)


def save_checkpoint(path: Path, model, config: TwoTowerConfig) -> None:
    """同时保存配置和完整 state_dict，加载时可校验所有参数。"""

    import torch

    torch.save({"config": asdict(config), "state_dict": model.state_dict()}, path)


def load_checkpoint(path: Path, device: str = "cpu"):
    import torch

    checkpoint = torch.load(path, map_location=device, weights_only=True)
    raw_config = checkpoint["config"]
    raw_config["hidden_dims"] = tuple(raw_config["hidden_dims"])
    config = TwoTowerConfig(**raw_config)
    model = create_model(config)
    missing, unexpected = model.load_state_dict(checkpoint["state_dict"], strict=False)
    if missing or unexpected:
        raise ValueError(f"模型参数不完整：missing={missing}, unexpected={unexpected}")
    return model.to(device), config
