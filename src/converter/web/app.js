/* 万能转换 · 前端逻辑：上传 → 选格式 → 转换（轮询进度）→ 下载 */
"use strict";

const $ = (id) => document.getElementById(id);
const API = {
  upload:  (name) => "/api/upload?name=" + encodeURIComponent(name),
  convert: "/api/convert",
  status:  (id) => "/api/status?id=" + encodeURIComponent(id),
  download:(id) => "/api/download/" + encodeURIComponent(id),
};
const LS_KEY = "fc_last_job";

let current = null;   // { fileId, fileName, size, targets, target }
let pollTimer = null;

/* ---------------- 状态切换 ---------------- */
const STATES = ["stUpload", "stPick", "stWorking", "stDone", "stFail"];
function show(state) {
  STATES.forEach((s) => { $(s).hidden = (s !== state); });
}

function toast(msg, ms = 2600) {
  const t = $("toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(t._timer);
  t._timer = setTimeout(() => { t.hidden = true; }, ms);
}

function humanSize(n) {
  if (n == null) return "—";
  if (n < 1024) return n + " B";
  if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
  if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
  return (n / 1073741824).toFixed(2) + " GB";
}

/* ---------------- 上传 ---------------- */
const dropZone = $("dropZone");
const fileInput = $("fileInput");

dropZone.addEventListener("click", () => fileInput.click());
dropZone.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") fileInput.click(); });
fileInput.addEventListener("change", () => {
  if (fileInput.files.length) uploadFile(fileInput.files[0]);
  fileInput.value = "";
});

["dragenter", "dragover"].forEach((ev) =>
  dropZone.addEventListener(ev, (e) => {
    e.preventDefault();
    dropZone.classList.add("dragover");
  }));
["dragleave", "drop"].forEach((ev) =>
  dropZone.addEventListener(ev, (e) => {
    e.preventDefault();
    dropZone.classList.remove("dragover");
  }));
dropZone.addEventListener("drop", (e) => {
  const f = e.dataTransfer.files && e.dataTransfer.files[0];
  if (f) uploadFile(f);
});
// 阻止把文件拖到页面其它位置时浏览器直接打开它
["dragover", "drop"].forEach((ev) =>
  window.addEventListener(ev, (e) => {
    if (!dropZone.contains(e.target)) e.preventDefault();
  }));

function uploadFile(file) {
  show("stUpload");
  $("workTitle").textContent = "正在上传…";
  $("workSub").textContent = file.name;
  // 复用进度区显示上传进度
  showProgressUI("正在上传…", file.name);

  const xhr = new XMLHttpRequest();
  xhr.open("POST", API.upload(file.name), true);
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) setProgress(e.loaded / e.total, false);
  };
  xhr.onload = () => {
    let res = null;
    try { res = JSON.parse(xhr.responseText); } catch (ignore) { }
    if (xhr.status === 200 && res && res.success) {
      current = {
        fileId: res.fileId,
        fileName: res.fileName,
        size: res.size,
        targets: res.targets || [],
        target: (res.targets || [])[0] || null,
      };
      localStorage.setItem(LS_KEY, JSON.stringify({ jobId: res.fileId }));
      if (res.ffmpeg === false) $("ffmpegWarn").hidden = false;
      else $("ffmpegWarn").hidden = true;
      showPick();
      toast("上传成功，请选择目标格式");
    } else {
      show("stUpload");
      toast((res && res.error) || "上传失败（" + xhr.status + "）");
    }
  };
  xhr.onerror = () => { show("stUpload"); toast("网络错误，上传失败"); };
  xhr.send(file);
}

/* ---------------- 选择目标格式 ---------------- */
function showPick() {
  $("pickName").textContent = current.fileName;
  $("pickName").title = current.fileName;
  $("pickSize").textContent = humanSize(current.size);
  $("srcExt").textContent = (current.fileName.split(".").pop() || "?").toUpperCase();

  const box = $("targetPills");
  box.innerHTML = "";
  current.targets.forEach((t, i) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "pill" + (i === 0 ? " sel" : "");
    b.textContent = t.toUpperCase();
    b.onclick = () => {
      current.target = t;
      box.querySelectorAll(".pill").forEach((p) => p.classList.remove("sel"));
      b.classList.add("sel");
    };
    box.appendChild(b);
  });
  show("stPick");
}

$("btnChangeFile").onclick = resetAll;
$("btnAnother").onclick = resetAll;
$("btnRetry").onclick = resetAll;

function resetAll() {
  current = null;
  localStorage.removeItem(LS_KEY);
  if (pollTimer) clearInterval(pollTimer);
  show("stUpload");
}

/* ---------------- 转换 ---------------- */
$("btnConvert").onclick = async () => {
  if (!current || !current.target) return;
  $("btnConvert").disabled = true;
  try {
    const r = await fetch(API.convert, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fileId: current.fileId, target: current.target }),
    });
    const res = await r.json();
    if (!r.ok || !res.success) throw new Error(res.error || "提交失败");
    showProgressUI("正在转换…", "由本机 FFmpeg / 转换引擎处理，请稍候");
    poll(res.jobId || current.fileId);
  } catch (e) {
    toast(e.message || "提交转换失败");
  } finally {
    $("btnConvert").disabled = false;
  }
};

function showProgressUI(title, sub) {
  show("stWorking");
  $("workTitle").textContent = title;
  $("workSub").textContent = sub || "";
  setProgress(0, true);
}

function setProgress(frac, indet) {
  const bar = $("progressBar");
  bar.classList.toggle("indet", !!indet);
  bar.style.width = (Math.round(frac * 100)) + "%";
  $("pctText").textContent = indet ? "处理中…" : Math.round(frac * 100) + "%";
}

function poll(jobId) {
  if (pollTimer) clearInterval(pollTimer);
  const tick = async () => {
    try {
      const r = await fetch(API.status(jobId));
      const s = await r.json();
      if (!r.ok || !s.success) throw new Error(s.error || "查询状态失败");
      if (s.state === "RUNNING") {
        const f = Number(s.progress) || 0;
        setProgress(f, f <= 0);
        return;
      }
      clearInterval(pollTimer);
      pollTimer = null;
      if (s.state === "DONE") showDone(s);
      else showFail(s.error || "请检查文件格式或重新尝试。");
    } catch (e) {
      clearInterval(pollTimer);
      pollTimer = null;
      showFail("无法连接转换服务，请确认服务已启动。");
    }
  };
  tick();
  pollTimer = setInterval(tick, 600);
}

/* ---------------- 成功 / 失败 ---------------- */
function showDone(s) {
  $("doneSrc").textContent = s.fileName || "";
  $("doneSrc").title = s.fileName || "";
  $("doneOut").textContent = s.outputName || "";
  $("doneOut").title = s.outputName || "";
  $("doneSize").textContent = humanSize(s.size);
  const a = $("btnDownload");
  a.href = API.download(jobIdOf(s));
  a.setAttribute("download", s.outputName || "");
  show("stDone");
}

function showFail(reason) {
  $("failReason").textContent = reason;
  show("stFail");
}

function jobIdOf(s) {
  // downloadUrl 形如 /api/download/<id>
  const m = /\/api\/download\/([A-Za-z0-9]+)/.exec(s.downloadUrl || "");
  return m ? m[1] : current && current.fileId;
}

/* ---------------- 刷新恢复：服务端任务 24h 内仍可下载 ---------------- */
window.addEventListener("DOMContentLoaded", async () => {
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem(LS_KEY)); } catch (ignore) { }
  if (!saved || !saved.jobId) return;
  try {
    const r = await fetch(API.status(saved.jobId));
    if (!r.ok) { localStorage.removeItem(LS_KEY); return; }
    const s = await r.json();
    if (s.state === "DONE") showDone(s);
    else if (s.state === "FAILED") showFail(s.error || "请检查文件格式或重新尝试。");
    else if (s.state === "RUNNING") { showProgressUI("正在转换…", s.fileName); poll(saved.jobId); }
  } catch (ignore) { }
});
