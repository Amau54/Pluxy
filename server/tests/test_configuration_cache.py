import pytest
from fastapi.testclient import TestClient

from pluxy.main import app


@pytest.mark.parametrize("path", ["/", "/settings.js", "/settings.css", "/desktop-updates.js", "/desktop-updates.css"])
def test_configuration_interface_is_refreshed_after_updates(path):
    client = TestClient(app, base_url="http://localhost", client=("127.0.0.1", 1234))
    try:
        response = client.get(path)
    finally:
        client.close()
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    if path == "/":
        assert 'data-desktop="serverStart"' in response.text
        assert 'data-desktop="serverStop"' in response.text
        assert 'data-desktop="serverRestart"' in response.text
        assert 'data-desktop="fullscreen"' not in response.text
