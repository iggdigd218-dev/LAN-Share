#!/usr/bin/env python3
"""تطبيق ويندوز — تشغيل خادم LAN Share وعرض PIN والعنوان."""

from __future__ import annotations

import io
import os
import socket
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

if getattr(sys, "frozen", False):
    ROOT = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
else:
    ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
try:
    os.chdir(ROOT)
except OSError:
    pass

import server as lanserver  # noqa: E402

try:
    from PIL import Image, ImageTk
except ImportError:
    Image = ImageTk = None  # type: ignore


def local_ip() -> str:
    return lanserver.local_ip()


class WinApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("LAN Share — الكمبيوتر")
        self.geometry("520x640")
        self.configure(bg="#0b1220")
        self.resizable(False, False)
        self._photo = None
        self._server_thread = None
        self._build()
        self.after(400, self.start_server)

    def _build(self) -> None:
        fg, muted, card = "#e8eefc", "#8ea0c4", "#121a2b"
        pad = {"padx": 20, "pady": 6}

        tk.Label(self, text="LAN Share", fg=fg, bg="#0b1220", font=("Segoe UI", 22, "bold")).pack(pady=(24, 4))
        tk.Label(
            self,
            text="شغّل هذا البرنامج على الكمبيوتر ثم افتح تطبيق الأندرويد على نفس شبكة المحل",
            fg=muted,
            bg="#0b1220",
            wraplength=460,
            justify="center",
            font=("Segoe UI", 10),
        ).pack(**pad)

        box = tk.Frame(self, bg=card)
        box.pack(fill="x", padx=24, pady=12)

        self.var_status = tk.StringVar(value="جاري تشغيل الخادم…")
        self.var_ip = tk.StringVar(value="—")
        self.var_pin = tk.StringVar(value=lanserver.PIN)

        def row(label, var, big=False):
            f = tk.Frame(box, bg=card)
            f.pack(fill="x", padx=16, pady=8)
            tk.Label(f, text=label, fg=muted, bg=card, font=("Segoe UI", 9)).pack(anchor="e")
            tk.Label(f, textvariable=var, fg=fg, bg=card, font=("Consolas", 18 if big else 13, "bold")).pack(anchor="e")

        row("الحالة", self.var_status)
        row("عنوان الكمبيوتر (للأندرويد)", self.var_ip)
        row("رمز PIN", self.var_pin, big=True)

        self.qr_label = tk.Label(self, bg="#0b1220")
        self.qr_label.pack(pady=8)

        btns = tk.Frame(self, bg="#0b1220")
        btns.pack(fill="x", padx=24, pady=8)

        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass

        tk.Button(
            btns,
            text="إضافة مجلد للمشاركة",
            command=self.add_folder,
            bg="#5b8cff",
            fg="white",
            relief="flat",
            font=("Segoe UI", 11, "bold"),
            padx=12,
            pady=10,
        ).pack(fill="x", pady=4)

        tk.Button(
            btns,
            text="نسخ العنوان",
            command=self.copy_url,
            bg="#1e2a44",
            fg="white",
            relief="flat",
            font=("Segoe UI", 11),
            padx=12,
            pady=10,
        ).pack(fill="x", pady=4)

        self.lst = tk.Listbox(self, height=6, bg=card, fg=fg, relief="flat", highlightthickness=0)
        self.lst.pack(fill="x", padx=24, pady=8)
        self.refresh_roots()

        tk.Label(
            self,
            text="على الهاتف: افتح تطبيق LAN Share → بحث تلقائي أو أدخل العنوان وPIN",
            fg=muted,
            bg="#0b1220",
            wraplength=460,
            font=("Segoe UI", 9),
        ).pack(pady=8)

    def refresh_roots(self) -> None:
        self.lst.delete(0, "end")
        for r in lanserver.ALLOWED_ROOTS:
            self.lst.insert("end", str(r))

    def add_folder(self) -> None:
        p = filedialog.askdirectory(title="اختر مجلداً لمشاركته مع الهاتف")
        if not p:
            return
        try:
            lanserver.add_share_root(p)
            self.refresh_roots()
        except Exception as e:
            messagebox.showerror("خطأ", str(e))

    def copy_url(self) -> None:
        url = f"http://{local_ip()}:{lanserver.HTTP_PORT}"
        self.clipboard_clear()
        self.clipboard_append(url)
        self.var_status.set("تم نسخ العنوان")

    def start_server(self) -> None:
        def run():
            import uvicorn

            lanserver.start_discovery()
            uvicorn.run(lanserver.app, host="0.0.0.0", port=lanserver.HTTP_PORT, log_level="warning")

        self._server_thread = threading.Thread(target=run, daemon=True)
        self._server_thread.start()
        ip = local_ip()
        self.var_ip.set(f"http://{ip}:{lanserver.HTTP_PORT}")
        self.var_status.set("يعمل — جاهز لاتصال الهاتف")
        self.show_qr()

    def show_qr(self) -> None:
        if Image is None:
            return
        import qrcode

        url = f"http://{local_ip()}:{lanserver.HTTP_PORT}"
        img = qrcode.make(url)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        buf.seek(0)
        im = Image.open(buf).resize((180, 180))
        self._photo = ImageTk.PhotoImage(im)
        self.qr_label.configure(image=self._photo)


def main() -> None:
    app = WinApp()
    app.mainloop()


if __name__ == "__main__":
    main()
