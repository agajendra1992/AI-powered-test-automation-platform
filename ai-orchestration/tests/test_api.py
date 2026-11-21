"""Phase 6c: the HTTP API (auth, roles, approve/reject, state, failure analysis) over the real graph with fakes."""
from __future__ import annotations

import json
import time

import pytest
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import InMemorySaver

from app.api import create_app
from app.security.auth import sha256_hex
from tests.test_workflow import FakeQa, deps

KEYS = {"alice": ("operator", "op-key-alice-0123456789"), "bob": ("approver", "ap-key-bob-0123456789xx"),
        "vera": ("viewer", "vw-key-vera-0123456789x")}


def h(user):
    return {"Authorization": f"Bearer {KEYS[user][1]}"}


@pytest.fixture
def client_for(tmp_path):
    keys = tmp_path / "keys.json"
    keys.write_text(json.dumps([{"name": n, "role": r, "sha256": sha256_hex(k)} for n, (r, k) in KEYS.items()]))

    def make(qa=None, **kw):
        d = deps(tmp_path, qa=qa, keys_file=keys, **kw)
        return TestClient(create_app(d.settings, d, InMemorySaver()))
    return make


def wait_for(c, wid, statuses, user="vera", timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        body = c.get(f"/ai/executions/{wid}", headers=h(user)).json()
        if body["status"] in statuses:
            return body
        time.sleep(0.05)
    raise AssertionError(f"status stayed {body['status']}")


def test_auth_and_roles(client_for):
    with client_for() as c:
        assert c.post("/ai/execute", json={"request": "run smoke"}).status_code == 401
        assert c.post("/ai/execute", json={"request": "run smoke"}, headers={"Authorization": "Bearer wrong-key-0123456789x"}).status_code == 401
        assert c.post("/ai/execute", json={"request": "run smoke"}, headers=h("vera")).status_code == 403
        assert c.get("/ai/executions/wf-0000000000000000", headers=h("vera")).status_code == 404
        assert c.get("/ai/executions/../../etc", headers=h("vera")).status_code in (400, 404)
        assert c.get("/health").json()["auth"] == "enabled"
        assert "ai_auth_failures_total" in c.get("/metrics").text


def test_execute_approve_complete(client_for):
    qa = FakeQa(statuses=["RUNNING", "PASSED"])
    with client_for(qa) as c:
        r = c.post("/ai/execute", json={"request": "Execute all payment regression scenarios in QA using Chromium"}, headers=h("alice"))
        assert r.status_code == 202
        wid = r.json()["workflow_id"]
        body = wait_for(c, wid, {"AWAITING_APPROVAL"})
        assert body["pending_approval"]["plan"]["test_cases"] == 10 and qa.runs == []
        assert c.post(f"/ai/executions/{wid}/approve", json={}, headers=h("vera")).status_code == 403  # viewer cannot approve
        ok = c.post(f"/ai/executions/{wid}/approve", json={"note": "go"}, headers=h("bob"))
        assert ok.status_code == 200, ok.text
        done = wait_for(c, wid, {"COMPLETED"})
        assert done["final"]["test_result"] == "PASSED" and done["execution_id"] == "run-20261120-120000-abcdef"
        assert c.post(f"/ai/executions/{wid}/approve", json={}, headers=h("bob")).status_code == 409  # not pending any more
        state = c.get(f"/ai/executions/{wid}/state", headers=h("vera")).json()
        assert state["state"]["approval"]["by"] == "bob" and "mcp_execution" in state["state"]["trail"]
        assert len(qa.runs) == 1


def test_reject_and_staging_needs_another_approver(client_for):
    qa = FakeQa()
    with client_for(qa) as c:
        wid = c.post("/ai/execute", json={"request": "run payment regression in qa on chromium"}, headers=h("alice")).json()["workflow_id"]
        wait_for(c, wid, {"AWAITING_APPROVAL"})
        assert c.post(f"/ai/executions/{wid}/approve", json={}, headers=h("alice")).status_code == 403  # no self-approval, even in qa
        assert c.post(f"/ai/executions/{wid}/reject", json={"note": "not today"}, headers=h("alice")).status_code == 200
        done = wait_for(c, wid, {"REJECTED"})
        assert done["final"]["test_result"] is None and qa.runs == []

        wid = c.post("/ai/execute", json={"request": "run payment regression in staging on chromium"}, headers=h("bob")).json()["workflow_id"]
        wait_for(c, wid, {"AWAITING_APPROVAL"})
        assert c.post(f"/ai/executions/{wid}/approve", json={}, headers=h("bob")).status_code == 403   # requester cannot self-approve staging
        assert c.post(f"/ai/executions/{wid}/approve", json={}, headers=h("alice")).status_code == 403  # operator is not an approver


def test_invalid_request_finishes_without_execution(client_for):
    qa = FakeQa()
    with client_for(qa) as c:
        wid = c.post("/ai/execute", json={"request": "run @nonexistent in qa"}, headers=h("alice")).json()["workflow_id"]
        done = wait_for(c, wid, {"INVALID", "NO_MATCH", "NEEDS_CLARIFICATION", "UNSUPPORTED"})
        assert qa.runs == [] and done["final"]["test_result"] is None


def test_failure_analysis_endpoint(client_for):
    qa = FakeQa(failed=["Login with valid credentials"])
    with client_for(qa) as c:
        assert c.post("/ai/failure-analysis", json={"execution_id": "bad id"}, headers=h("alice")).status_code == 422
        r = c.post("/ai/failure-analysis", json={"execution_id": "run-20261120-120000-abcdef"}, headers=h("alice"))
        assert r.status_code == 200, r.text
        a = r.json()["analyses"][0]
        assert a["scenario"] == "Login with valid credentials" and a["rule_category"] == "AUTHENTICATION_FAILURE" and a["test_result"].startswith("FAILED")
        assert "hypotheses" in r.json()["note"]
