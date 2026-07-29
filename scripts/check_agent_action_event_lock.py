from __future__ import annotations

import os
import queue
import threading
import time
import uuid
from collections.abc import Callable
from typing import Any

import pymysql


def required(name: str) -> str:
    value = os.environ.get(name)
    if value is None or not value:
        raise RuntimeError(f"{name} is required")
    return value


def connect(user: str, password: str) -> pymysql.Connection[Any]:
    return pymysql.connect(
        host=required("MYSQL_HOST"),
        port=int(required("MYSQL_PORT")),
        user=user,
        password=password,
        database="cs_db",
        autocommit=False,
        connect_timeout=2,
        read_timeout=5,
        write_timeout=5,
        charset="utf8mb4",
    )


def main() -> None:
    pending_action_id = required("PENDING_ACTION_ID")
    agent = connect("agent_app", required("MYSQL_AGENT_APP_PASSWORD"))
    root = connect("root", required("MYSQL_ROOT_PASSWORD"))
    try:
        with agent.cursor() as cursor:
            cursor.execute("SELECT CURRENT_USER(), CURRENT_ROLE()")
            if cursor.fetchone() != ("agent_app@%", "NONE"):
                raise RuntimeError("The event-lock witness did not use the agent runtime identity")
            cursor.execute(
                "SELECT source_turn_id, source_trace_id, session_id, user_subject "
                "FROM pending_action_reference WHERE pending_action_id = %s",
                (pending_action_id,),
            )
            pending_row = cursor.fetchone()
        if pending_row is None:
            raise RuntimeError("The event-lock witness PendingAction is missing")
        source_turn_id, source_trace_id, session_id, user_subject = pending_row

        with root.cursor() as cursor:
            cursor.execute(
                "SELECT event_id, sequence, event_type, payload_json "
                "FROM support_event WHERE turn_id = %s ORDER BY sequence LIMIT 49",
                (source_turn_id,),
            )
            turn_events = cursor.fetchall()
        event_rows = [row for row in turn_events if row[2] == "ACTION_PREPARED"]
        if len(event_rows) != 1:
            raise RuntimeError("The event-lock witness requires one preparation event")
        event_id, _, _, payload_json = event_rows[0]

        sibling_event_id = str(uuid.uuid4())
        with root.cursor() as cursor:
            cursor.execute(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM support_event WHERE turn_id = %s",
                (source_turn_id,),
            )
            sibling_sequence = int(cursor.fetchone()[0])

        def update_existing(cursor: pymysql.cursors.Cursor) -> None:
            cursor.execute(
                "UPDATE support_event "
                "SET payload_json = JSON_SET(payload_json, '$.lockWitness', TRUE) "
                "WHERE event_id = %s",
                (event_id,),
            )

        def delete_existing(cursor: pymysql.cursors.Cursor) -> None:
            cursor.execute("DELETE FROM support_event WHERE event_id = %s", (event_id,))

        def insert_sibling(cursor: pymysql.cursors.Cursor) -> None:
            cursor.execute(
                "INSERT INTO support_event "
                "(event_id, turn_id, trace_id, session_id, user_subject, sequence, "
                "event_type, payload_json) VALUES (%s, %s, %s, %s, %s, %s, "
                "'ACTION_PREPARED', %s)",
                (
                    sibling_event_id,
                    source_turn_id,
                    source_trace_id,
                    session_id,
                    user_subject,
                    sibling_sequence,
                    payload_json,
                ),
            )

        operations: tuple[tuple[str, Callable[[pymysql.cursors.Cursor], None]], ...] = (
            ("privileged UPDATE", update_existing),
            ("privileged DELETE", delete_existing),
            ("same-turn ACTION_PREPARED sibling INSERT", insert_sibling),
        )
        for label, operation in operations:
            agent.begin()
            try:
                with agent.cursor() as cursor:
                    cursor.execute(
                        "SELECT event_id, trace_id, session_id, user_subject, sequence, "
                        "event_type, payload_json FROM support_event WHERE turn_id = %s "
                        "ORDER BY sequence LIMIT 49 FOR SHARE",
                        (source_turn_id,),
                    )
                    locked_rows = cursor.fetchall()
                    if (
                        len(locked_rows) != len(turn_events)
                        or sum(row[5] == "ACTION_PREPARED" for row in locked_rows) != 1
                    ):
                        raise RuntimeError("The locked preparation-event cardinality changed")

                started = threading.Event()
                result: queue.Queue[tuple[str, object]] = queue.Queue(maxsize=1)

                def run_sibling(
                    sibling_operation: Callable[[pymysql.cursors.Cursor], None] = operation,
                    sibling_started: threading.Event = started,
                    sibling_result: queue.Queue[tuple[str, object]] = result,
                ) -> None:
                    sibling = connect("root", required("MYSQL_ROOT_PASSWORD"))
                    try:
                        sibling.begin()
                        with sibling.cursor() as cursor:
                            cursor.execute("SET SESSION innodb_lock_wait_timeout = 5")
                            sibling_started.set()
                            sibling_operation(cursor)
                            sibling_result.put(("completed", cursor.rowcount))
                        sibling.rollback()
                    except BaseException as exception:
                        sibling.rollback()
                        sibling_result.put(("failed", exception))
                    finally:
                        sibling.close()

                worker = threading.Thread(target=run_sibling, daemon=True)
                worker.start()
                if not started.wait(timeout=2):
                    raise RuntimeError(f"{label} did not start")

                deadline = time.monotonic() + 3
                wait_seen = False
                while worker.is_alive() and time.monotonic() < deadline:
                    with root.cursor() as cursor:
                        cursor.execute(
                            "SELECT COUNT(*) "
                            "FROM performance_schema.data_lock_waits waits "
                            "JOIN performance_schema.data_locks requested "
                            "ON requested.engine_lock_id = waits.requesting_engine_lock_id "
                            "WHERE requested.object_schema = 'cs_db' "
                            "AND requested.object_name = 'support_event'"
                        )
                        wait_seen = int(cursor.fetchone()[0]) > 0
                    if wait_seen:
                        break
                if not wait_seen:
                    raise RuntimeError(f"{label} did not wait on the preparation-event share lock")
            finally:
                agent.rollback()

            worker.join(timeout=5)
            if worker.is_alive():
                raise RuntimeError(f"{label} did not finish after the share lock ended")
            outcome, value = result.get_nowait()
            if outcome != "completed":
                assert isinstance(value, BaseException)
                raise RuntimeError(f"{label} failed after the share lock ended") from value
            if value != 1:
                raise RuntimeError(f"{label} did not affect exactly one row")

            with root.cursor() as cursor:
                cursor.execute(
                    "SELECT payload_json FROM support_event WHERE event_id = %s",
                    (event_id,),
                )
                if cursor.fetchone() != (payload_json,):
                    raise RuntimeError(f"{label} changed the preparation event")
                cursor.execute(
                    "SELECT COUNT(*) FROM support_event "
                    "WHERE turn_id = %s AND event_type = 'ACTION_PREPARED'",
                    (source_turn_id,),
                )
                if cursor.fetchone()[0] != 1:
                    raise RuntimeError(f"{label} changed preparation-event cardinality")

            print(f"Verified event share-lock wait-until-release: {label}")
    finally:
        agent.rollback()
        root.rollback()
        agent.close()
        root.close()


if __name__ == "__main__":
    main()
