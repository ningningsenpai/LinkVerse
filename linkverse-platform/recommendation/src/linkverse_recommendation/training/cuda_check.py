"""GPU 训练前置检查。"""

from __future__ import annotations

import json
import os


def require_cuda() -> dict[str, object]:
    """验证 CUDA、设备名称、显存和运行时；失败时禁止静默回退 CPU。"""

    # CUDA 10.2+ 的确定性矩阵乘法要求在首次创建 cuBLAS 工作区前设置该值。
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    import torch

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA 不可用，已停止 GPU 训练")
    device = torch.cuda.current_device()
    properties = torch.cuda.get_device_properties(device)
    result = {
        "available": True,
        "device_index": device,
        "device_name": properties.name,
        "memory_bytes": properties.total_memory,
        "torch_version": torch.__version__,
        "cuda_runtime": torch.version.cuda,
    }
    if not result["cuda_runtime"]:
        raise RuntimeError("PyTorch 未包含 CUDA 运行时")
    return result


def main() -> None:
    print(json.dumps(require_cuda(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
