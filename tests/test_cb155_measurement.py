import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.measure_cb155 import JMETER_SHA512, JMETER_VERSION, PROFILE_ID, SQL_BLOCKS  # noqa: E402


def test_frozen_profile_and_sql_inventory() -> None:
    assert PROFILE_ID == "cb155-formal-v1"
    assert JMETER_VERSION == "5.6.3"
    assert len(JMETER_SHA512) == 128
    assert [name for name, _ in SQL_BLOCKS] == [
        "Q01",
        "Q02",
        "Q03",
        "Q04",
        "Q05",
        "Q06",
        "Q07a",
        "Q07b",
        "Q08",
        "Q09",
    ]
