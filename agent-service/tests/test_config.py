import pytest
from app.core.config import Settings
from pydantic import ValidationError


def test_settings_default_to_mock_profile(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("APP_PROFILE", raising=False)

    settings = Settings(_env_file=None)

    assert settings.app_profile == "mock"
    assert settings.service_name == "travelstar-agent-service"
    assert settings.host == "127.0.0.1"
    assert settings.port == 8000


def test_settings_accept_real_profile_from_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("APP_PROFILE", "real")

    settings = Settings(_env_file=None)

    assert settings.app_profile == "real"


def test_settings_reject_invalid_profile(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("APP_PROFILE", "invalid")

    with pytest.raises(ValidationError):
        Settings(_env_file=None)


@pytest.mark.parametrize("port", ["0", "65536", "not-a-number"])
def test_settings_reject_invalid_port(
    monkeypatch: pytest.MonkeyPatch,
    port: str,
) -> None:
    monkeypatch.setenv("AGENT_SERVICE_PORT", port)

    with pytest.raises(ValidationError):
        Settings(_env_file=None)
