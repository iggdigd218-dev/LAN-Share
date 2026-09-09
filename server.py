#!/usr/bin/env python3
"""LAN Share — خادم المشاركة عبر الشبكة المحلية (ويندوز + أندرويد)."""

from __future__ import annotations

import io
import json
import mimetypes
import os
import secrets
import socket
import string
import threading
from pathlib import Path
from urllib.parse import unquote

import qrcode
from fastapi import FastAPI, File, Header, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"
HOME = Path.home()
HTTP_PORT = 8080
DISCOVER_PORT = 45454

PIN = "".join(secrets.choice(string.digits) for _ in range(6))
TOKEN = secrets.token_urlsafe(24)
(ROOT / ".pin").write_text(PIN, encoding="utf-8")

ALLOWED_ROOTS: list[Path] = []


def default_roots() -> list[Path]:
    roots: list[Path] = []
    if HOME.exists():
        roots.append(HOME.resolve())
    if os.name == "nt":
        for letter in string.ascii_uppercase:
            p = Path(f"{letter}:/")
            try:
                if p.exists():
                    roots.append(p.resolve())
            except OSError:
                continue
        for extra in [HOME / "Desktop", HOME / "Documents", HOME / "Downloads", HOME / "Music", HOME / "Videos"]:
            if extra.exists():
                roots.append(extra.resolve())
    else:
        for p in [Path("/home"), Path("/media"), Path("/mnt"), Path("/tmp")]:
            if p.exists():
                roots.append(p.resolve())
    # unique
    seen: set[str] = set()
    out: list[Path] = []
    for r in roots:
        k = str(r)
        if k not in seen:
            seen.add(k)
            out.append(r)
    return out


ALLOWED_ROOTS.extend(default_roots())

app = FastAPI(title="LAN Share")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
if STATIC.exists():
    app.mount("/assets", StaticFiles(directory=str(STATIC)), name="assets")


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def is_allowed(path: Path) -> bool:
    try:
        resolved = path.resolve()
    except Exception:
        return False
    for root in ALLOWED_ROOTS:
        try:
            resolved.relative_to(root)
            return True
        except ValueError:
            continue
    return False


def require_auth(authorization: str | None) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "مطلوب تسجيل الدخول")
    if authorization.split(" ", 1)[1] != TOKEN:
        raise HTTPException(401, "رمز غير صالح")


def human_size(n: int) -> str:
    for unit in ("بايت", "ك.ب", "م.ب", "ج.ب", "ت.ب"):
        if n < 1024:
            return f"{n:.0f} {unit}" if unit == "بايت" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} ب.ب"


def file_kind(path: Path) -> str:
    ext = path.suffix.lower()
    if ext in {".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".opus", ".wma"}:
        return "audio"
    if ext in {".mp4", ".webm", ".mkv", ".avi", ".mov", ".m4v", ".ogv"}:
        return "video"
    if ext in {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg"}:
        return "image"
    if ext in {".pdf", ".txt", ".md", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"}:
        return "doc"
    return "file"


def add_share_root(path: str) -> str:
    p = Path(path).expanduser().resolve()
    if not p.is_dir():
        raise ValueError("ليس مجلداً")
    if p not in ALLOWED_ROOTS:
        ALLOWED_ROOTS.append(p)
    return str(p)


@app.get("/", response_class=HTMLResponse)
def index():
    html = STATIC / "index.html"
    if html.exists():
        return html.read_text(encoding="utf-8")
    return "<h1>LAN Share</h1>"


@app.get("/api/hello")
def hello():
    return {
        "name": "LAN Share",
        "ip": local_ip(),
        "port": HTTP_PORT,
        "home": str(HOME),
        "computer": socket.gethostname(),
    }


@app.get("/api/info")
def info():
    return {
        "ip": local_ip(),
        "pin_hint": PIN[:2] + "****",
        "home": str(HOME),
        "computer": socket.gethostname(),
        "roots": [{"name": r.name or str(r), "path": str(r)} for r in ALLOWED_ROOTS],
    }


class LoginBody(BaseModel):
    pin: str = ""


class ShareBody(BaseModel):
    path: str = ""


@app.post("/api/login")
def login(body: LoginBody):
    pin = str(body.pin).strip()
    if pin != PIN:
        raise HTTPException(403, "رمز PIN غير صحيح")
    return {"token": TOKEN, "home": str(HOME), "computer": socket.gethostname()}


@app.post("/api/share")
def share(body: ShareBody, authorization: str | None = Header(default=None)):
    # يسمح من واجهة الكمبيوتر المحلية بدون توكن إن كان من 127.0.0.1 عبر الواجهة
    if authorization:
        require_auth(authorization)
    try:
        p = add_share_root(body.path)
    except ValueError as e:
        raise HTTPException(400, str(e))
    return {"ok": True, "path": p}


@app.get("/api/qr")
def qr_png():
    url = f"http://{local_ip()}:{HTTP_PORT}"
    img = qrcode.make(url)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    buf.seek(0)
    return StreamingResponse(buf, media_type="image/png")


@app.get("/api/list")
def list_dir(
    path: str = Query(default=""),
    authorization: str | None = Header(default=None),
):
    require_auth(authorization)
    target = Path(unquote(path)).expanduser() if path else HOME
    if not is_allowed(target) or not target.is_dir():
        raise HTTPException(403, "المسار غير مسموح أو ليس مجلداً")

    items = []
    try:
        entries = list(target.iterdir())
    except PermissionError:
        raise HTTPException(403, "لا صلاحية للوصول")

    for e in entries:
        if e.name.startswith("."):
            continue
        try:
            st = e.stat()
        except OSError:
            continue
        kind = "dir" if e.is_dir() else file_kind(e)
        items.append(
            {
                "name": e.name,
                "path": str(e),
                "is_dir": e.is_dir(),
                "kind": kind,
                "size": 0 if e.is_dir() else st.st_size,
                "size_h": "" if e.is_dir() else human_size(st.st_size),
                "mtime": int(st.st_mtime),
            }
        )

    items.sort(key=lambda x: (not x["is_dir"], x["name"].lower()))
    parent = None
    try:
        if is_allowed(target.parent) and target.resolve() != HOME.resolve():
            parent = str(target.parent)
    except Exception:
        parent = None
    roots = [{"name": r.name or str(r), "path": str(r)} for r in ALLOWED_ROOTS]
    return {"path": str(target), "parent": parent, "items": items, "roots": roots}


@app.get("/api/search")
def search(
    q: str = Query(default=""),
    path: str = Query(default=""),
    authorization: str | None = Header(default=None),
):
    require_auth(authorization)
    q = q.strip().lower()
    if len(q) < 2:
        return {"items": []}
    base = Path(unquote(path)).expanduser() if path else HOME
    if not is_allowed(base):
        raise HTTPException(403, "مسار غير مسموح")
    found = []
    for root, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if not d.startswith(".")][:40]
        for name in files:
            if q in name.lower():
                p = Path(root) / name
                try:
                    st = p.stat()
                except OSError:
                    continue
                found.append(
                    {
                        "name": name,
                        "path": str(p),
                        "is_dir": False,
                        "kind": file_kind(p),
                        "size": st.st_size,
                        "size_h": human_size(st.st_size),
                    }
                )
                if len(found) >= 80:
                    return {"items": found}
        if len(found) >= 80:
            break
    return {"items": found}


@app.get("/api/download")
def download(
    path: str,
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
):
    if token != TOKEN:
        require_auth(authorization)
    target = Path(unquote(path)).resolve()
    if not is_allowed(target) or not target.is_file():
        raise HTTPException(404, "الملف غير موجود")
    return FileResponse(
        str(target),
        filename=target.name,
        media_type=mimetypes.guess_type(str(target))[0] or "application/octet-stream",
    )


@app.get("/api/stream")
def stream(
    path: str,
    range: str | None = Header(default=None),
    token: str | None = Query(default=None),
    authorization: str | None = Header(default=None),
):
    if token != TOKEN:
        require_auth(authorization)
    target = Path(unquote(path)).resolve()
    if not is_allowed(target) or not target.is_file():
        raise HTTPException(404, "الملف غير موجود")
    file_size = target.stat().st_size
    mime = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
    start, end = 0, file_size - 1
    status = 200
    if range and range.startswith("bytes="):
        part = range.split("=", 1)[1]
        s, _, e = part.partition("-")
        if s:
            start = int(s)
        if e:
            end = int(e)
        end = min(end, file_size - 1)
        status = 206

    def iterfile():
        with open(target, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = f.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    headers = {
        "Content-Length": str(end - start + 1),
        "Accept-Ranges": "bytes",
        "Content-Type": mime,
    }
    if status == 206:
        headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
    return StreamingResponse(iterfile(), status_code=status, headers=headers)


@app.post("/api/upload")
async def upload(
    dest: str = Query(default=""),
    file: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    require_auth(authorization)
    folder = Path(unquote(dest)).expanduser() if dest else HOME
    if not is_allowed(folder) or not folder.is_dir():
        raise HTTPException(403, "مجلد غير مسموح")
    name = Path(file.filename or "upload").name
    out = folder / name
    data = await file.read()
    out.write_bytes(data)
    return {"ok": True, "path": str(out), "size": len(data)}


def discovery_loop(stop: threading.Event) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("0.0.0.0", DISCOVER_PORT))
    except OSError:
        return
    sock.settimeout(1.0)
    payload = json.dumps(
        {
            "app": "LANSHARE",
            "name": socket.gethostname(),
            "ip": local_ip(),
            "port": HTTP_PORT,
        }
    ).encode("utf-8")
    while not stop.is_set():
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            continue
        except OSError:
            break
        msg = data.decode("utf-8", errors="ignore")
        if "LANSHARE_DISCOVER" in msg:
            try:
                sock.sendto(b"LANSHARE_OK|" + payload, addr)
            except OSError:
                pass
    sock.close()


_stop = threading.Event()


def start_discovery() -> None:
    t = threading.Thread(target=discovery_loop, args=(_stop,), daemon=True)
    t.start()


def main():
    import uvicorn

    start_discovery()
    ip = local_ip()
    print("\n" + "=" * 50)
    print("  LAN Share — ويندوز + أندرويد")
    print("=" * 50)
    print(f"  العنوان:  http://{ip}:{HTTP_PORT}")
    print(f"  PIN:      {PIN}")
    print("=" * 50 + "\n")
    uvicorn.run(app, host="0.0.0.0", port=HTTP_PORT, log_level="warning")


if __name__ == "__main__":
    main()
