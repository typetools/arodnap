import unittest

from arodnap.stages.registry import REPAIR_STAGE_ORDER, REPAIR_STAGE_REGISTRY, get_repair_stage_definition


class StageRegistryTest(unittest.TestCase):
    def test_repair_stage_registry_declares_canonical_order(self) -> None:
        self.assertEqual(
            REPAIR_STAGE_ORDER,
            ("close_injector", "owning_field", "rlfixer", "rlpatcher"),
        )
        self.assertEqual(
            tuple(stage.name for stage in REPAIR_STAGE_REGISTRY),
            REPAIR_STAGE_ORDER,
        )

    def test_repair_stage_registry_tracks_rerun_labels_for_mutating_stages(self) -> None:
        self.assertEqual(
            [stage.rerun_analysis_label for stage in REPAIR_STAGE_REGISTRY],
            ["post_close_injector", "post_owning_field", None, None],
        )
        self.assertEqual(
            get_repair_stage_definition("close_injector").rerun_analysis_label,
            "post_close_injector",
        )

    def test_unknown_stage_lookup_fails_closed(self) -> None:
        with self.assertRaisesRegex(KeyError, "Unknown repair stage"):
            get_repair_stage_definition("unknown")


if __name__ == "__main__":
    unittest.main()
