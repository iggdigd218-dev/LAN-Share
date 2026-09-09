const $ = (s) => document.querySelector(s);
let token = sessionStorage.getItem("lan_token") || "";
let currentPath = "";

const ICO = {
  dir: "📁",
  audio: "🎵",
  video: "🎬",
  image: "🖼️",
  doc: "📄",
  file: "📦",
};

function authHeaders() {
  return { Authorization: "Bearer " + token };
}

async function api(url, opts = {}) {
  const r = await fetch(url, {
    ...opts,
    headers: { ...(opts.headers || {}), ...authHeaders() },
  });
  if (r.status === 401 || r.status === 403) {
    if (!url.includes("/api/login")) {
      token = "";
      sessionStorage.removeItem("lan_token");
      showLogin();
    }
  }
  return r;
}

function showLogin() {
  $("#login").hidden = false;
  $("#main").hidden = true;
}

function showMain() {
  $("#login").hidden = true;
  $("#main").hidden = false;
}

$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("#login-err").hidden = true;
  const pin = $("#pin").value.trim();
  const r = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pin }),
  });
  if (!r.ok) {
    $("#login-err").textContent = "رمز PIN غير صحيح";
    $("#login-err").hidden = false;
    return;
  }
  const data = await r.json();
  token = data.token;
  sessionStorage.setItem("lan_token", token);
  currentPath = data.home;
  showMain();
  load(currentPath);
});

$("#btn-back").addEventListener("click", () => {
  if (!currentPath) return;
  const parent = currentPath.replace(/\\/g, "/").replace(/\/[^/]+$/, "") || currentPath;
  load(parent);
});

$("#btn-up").addEventListener("click", () => $("#file-input").click());
$("#file-input").addEventListener("change", async (e) => {
  const files = [...e.target.files];
  for (const f of files) {
    const fd = new FormData();
    fd.append("file", f);
    await api("/api/upload?dest=" + encodeURIComponent(currentPath), {
      method: "POST",
      body: fd,
      headers: authHeaders(),
    });
  }
  e.target.value = "";
  load(currentPath);
});

let searchTimer;
$("#search").addEventListener("input", () => {
  clearTimeout(searchTimer);
  const q = $("#search").value.trim();
  searchTimer = setTimeout(async () => {
    if (q.length < 2) {
      load(currentPath);
      return;
    }
    const r = await api(
      "/api/search?q=" + encodeURIComponent(q) + "&path=" + encodeURIComponent(currentPath)
    );
    const data = await r.json();
    renderList(data.items || [], true);
  }, 300);
});

async function load(path) {
  const r = await api("/api/list?path=" + encodeURIComponent(path || ""));
  if (!r.ok) return;
  const data = await r.json();
  currentPath = data.path;
  $("#path-label").textContent = data.path;
  $("#crumbs").textContent = data.path;
  renderList(data.items || []);
}

function renderList(items, searching) {
  const list = $("#list");
  list.innerHTML = "";
  $("#empty").hidden = items.length > 0;
  for (const it of items) {
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `
      <div class="ico">${ICO[it.kind] || ICO.file}</div>
      <div class="meta">
        <div class="n">${escapeHtml(it.name)}</div>
        <div class="s">${it.is_dir ? "مجلد" : it.size_h}${searching ? " — " + escapeHtml(it.path) : ""}</div>
      </div>
    `;
    row.addEventListener("click", () => openItem(it));
    list.appendChild(row);
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[c]));
}

function streamUrl(path) {
  return "/api/stream?path=" + encodeURIComponent(path) + "&token=" + encodeURIComponent(token);
}
function dlUrl(path) {
  return "/api/download?path=" + encodeURIComponent(path) + "&token=" + encodeURIComponent(token);
}

function openItem(it) {
  if (it.is_dir) {
    load(it.path);
    return;
  }
  const player = $("#player");
  const vid = $("#vid");
  const aud = $("#aud");
  vid.pause();
  aud.pause();
  vid.removeAttribute("src");
  aud.removeAttribute("src");
  vid.hidden = true;
  aud.hidden = true;
  $("#player-title").textContent = it.name;
  $("#dl-link").href = dlUrl(it.path);
  $("#dl-link").setAttribute("download", it.name);
  player.hidden = false;

  if (it.kind === "video") {
    vid.hidden = false;
    vid.src = streamUrl(it.path);
    vid.play().catch(() => {});
  } else if (it.kind === "audio") {
    aud.hidden = false;
    aud.src = streamUrl(it.path);
    aud.play().catch(() => {});
  } else if (it.kind === "image") {
    vid.hidden = false;
    vid.poster = streamUrl(it.path);
    vid.removeAttribute("src");
    // show image via video poster isn't ideal — use location
    window.open(streamUrl(it.path), "_blank");
  } else {
    window.location.href = dlUrl(it.path);
    player.hidden = true;
  }
}

$("#player-close").addEventListener("click", () => {
  $("#vid").pause();
  $("#aud").pause();
  $("#player").hidden = true;
});

if (token) {
  showMain();
  load("");
} else {
  showLogin();
}
