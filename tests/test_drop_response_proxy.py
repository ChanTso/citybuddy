import runpy
from pathlib import Path

DropState = runpy.run_path(
    str(Path(__file__).resolve().parents[1] / "scripts" / "drop_response_proxy.py")
)["DropState"]


def test_drop_state_can_target_one_http_method_without_consuming_other_requests() -> None:
    state = DropState("http://upstream.test:9200", "/index/_doc/source", 1, "PUT")

    assert state.should_drop("GET", "/index/_doc/source") is False
    assert state.should_drop("PUT", "/other/_doc/source") is False
    assert state.should_drop("PUT", "/index/_doc/source?if_seq_no=1") is True
    assert state.should_drop("PUT", "/index/_doc/source?if_seq_no=2") is False
