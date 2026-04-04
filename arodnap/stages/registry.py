from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RepairStageDefinition:
    name: str
    rerun_analysis_label: str | None = None


REPAIR_STAGE_REGISTRY: tuple[RepairStageDefinition, ...] = (
    RepairStageDefinition(name="close_injector", rerun_analysis_label="post_close_injector"),
    RepairStageDefinition(name="owning_field", rerun_analysis_label="post_owning_field"),
    RepairStageDefinition(name="rlfixer"),
    RepairStageDefinition(name="rlpatcher"),
)

REPAIR_STAGE_ORDER: tuple[str, ...] = tuple(stage.name for stage in REPAIR_STAGE_REGISTRY)


def get_repair_stage_definition(name: str) -> RepairStageDefinition:
    for stage in REPAIR_STAGE_REGISTRY:
        if stage.name == name:
            return stage
    raise KeyError(f"Unknown repair stage: {name}")


__all__ = [
    "REPAIR_STAGE_ORDER",
    "REPAIR_STAGE_REGISTRY",
    "RepairStageDefinition",
    "get_repair_stage_definition",
]
