"""QA Tests for LAN Share server."""

import io
import os
from pathlib import Path
import pytest
from starlette.testclient import TestClient
import server

@pytest.fixture
def client():
    return TestClient(server.app)

def test_hello_endpoint(client):
    r = client.get("/api/hello")
    assert r.status_code == 200
    data = r.json()
    assert data["name"] == "LAN Share"
    assert "ip" in data
    assert "port" in data
    assert "home" in data

def test_info_endpoint(client):
    r = client.get("/api/info")
    assert r.status_code == 200
    data = r.json()
    assert "ip" in data
    assert "pin_hint" in data
    assert "roots" in data
    assert len(data["roots"]) > 0

def test_login_failure(client):
    r = client.post("/api/login", json={"pin": "000000_wrong"})
    assert r.status_code == 403

def test_login_success(client):
    r = client.post("/api/login", json={"pin": server.PIN})
    assert r.status_code == 200
    data = r.json()
    assert "token" in data
    assert data["token"] == server.TOKEN

def test_list_requires_auth(client):
    r = client.get("/api/list")
    assert r.status_code == 401

def test_list_authenticated(client):
    token = server.TOKEN
    r = client.get("/api/list", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    data = r.json()
    assert "items" in data
    assert "path" in data
    assert "roots" in data

def test_qr_code(client):
    r = client.get("/api/qr")
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    assert len(r.content) > 100

def test_upload_download_stream(client):
    token = server.TOKEN
    auth_header = {"Authorization": f"Bearer {token}"}
    
    # 1. Upload
    test_data = b"LAN Share automated test file stream/download validation"
    files = {"file": ("lan_test.txt", io.BytesIO(test_data), "text/plain")}
    r = client.post(f"/api/upload?dest={server.HOME}", files=files, headers=auth_header)
    assert r.status_code == 200
    uploaded_path = r.json()["path"]

    try:
        # 2. Download via Auth header
        r = client.get(f"/api/download?path={uploaded_path}", headers=auth_header)
        assert r.status_code == 200
        assert r.content == test_data

        # 3. Download via Token query param (used by mobile apps)
        r = client.get(f"/api/download?path={uploaded_path}&token={token}")
        assert r.status_code == 200
        assert r.content == test_data

        # 4. Stream with Range header
        r = client.get(
            f"/api/stream?path={uploaded_path}&token={token}",
            headers={"Range": "bytes=0-8"}
        )
        assert r.status_code == 206
        assert r.content == test_data[0:9]
        assert r.headers["content-range"] == f"bytes 0-8/{len(test_data)}"
    finally:
        # Cleanup
        if os.path.exists(uploaded_path):
            os.remove(uploaded_path)

def test_static_html(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "LAN Share" in r.text
