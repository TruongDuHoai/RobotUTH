import pytest

from server.middleware.robot_api_auth import is_robot_request_authorized, requires_robot_token


def test_robot_bearer_token():
    tokens = ("test-token",)
    assert is_robot_request_authorized("Bearer test-token", tokens)
    assert not is_robot_request_authorized(None, tokens)
    assert not is_robot_request_authorized("Bearer wrong", tokens)
    assert not is_robot_request_authorized("Basic test-token", tokens)


def test_empty_token_config_keeps_lan_dev_open():
    assert is_robot_request_authorized(None, ())


@pytest.mark.parametrize("method", ["GET", "POST", "PUT", "PATCH", "DELETE"])
def test_robot_paths_gated_on_every_method(method):
    assert requires_robot_token(method, "/api/chat/query")
    assert requires_robot_token(method, "/api/tts/synthesize")
    assert requires_robot_token(method, "/asr/ctc/chunk")


def test_issues_prefix_gated():
    assert requires_robot_token("PUT", "/api/issues/abc-123")
    assert requires_robot_token("GET", "/api/issues/abc-123")


@pytest.mark.parametrize("method", ["POST", "PUT", "PATCH", "DELETE"])
def test_admin_writes_require_token(method):
    assert requires_robot_token(method, "/api/knowledge/documents")
    assert requires_robot_token(method, "/api/settings")


def test_admin_reads_stay_open():
    # The kiosk renders knowledge/settings status without a token.
    assert not requires_robot_token("GET", "/api/knowledge/documents")
    assert not requires_robot_token("GET", "/api/settings")


def test_unrelated_paths_not_gated():
    assert not requires_robot_token("POST", "/health")
    assert not requires_robot_token("POST", "/api/conversation/text")
