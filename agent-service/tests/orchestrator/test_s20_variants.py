"""S20 plan-count, shared collection and concrete alternative coverage."""

from __future__ import annotations

from app.orchestrator import service


def test_three_plans_share_one_collection_and_preserve_must_visit(
    deps, request_payload, monkeypatch
):
    runs = 0
    collect = service.run_collect

    def counted_collect(*args, **kwargs):
        nonlocal runs
        runs += 1
        return collect(*args, **kwargs)

    monkeypatch.setattr(service, "run_collect", counted_collect)
    prior = {}
    for step in service.STEPS:
        prior[step] = service.run_stage(
            step, {"request": {**request_payload, "plan_count": 3}, "prior": prior}, deps
        )
        assert prior[step]["status"] != "failed", prior[step]
    assert runs == 1
    variants = prior["assemble"]["variants"]
    assert len(variants) == len({variant["variant_id"] for variant in variants}) == 3
    assert len({variant["shared_snapshot_hash"] for variant in variants}) == 1
    schedules = {
        tuple(
            (segment["point_id"], (segment["transport_to_next"] or {}).get("mode"))
            for day in variant["plan"]["days"]
            for segment in day["segments"]
        )
        for variant in variants
    }
    assert len(schedules) == 3
    for variant in variants:
        assert variant["quality_score"] is not None
        assert any(
            segment["point_id"] == "POI-BJ-001" and segment["is_must_visit"]
            for day in variant["plan"]["days"]
            for segment in day["segments"]
        )
