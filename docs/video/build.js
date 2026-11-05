// 시연 영상 v2: 차량 화면 프레임 + 장면별 확대 + 장면 제목 + 전환 + 배경음 + 자막 (ffmpeg)
// 사용: node build.js   (작업 폴더 C:\Android\video 에 raw/ 녹화본과 tts/ 내레이션이 있어야 함)
// 결과: C:\Android\video\v2\RecoDate_Drive_demo.mp4 와 sub.srt
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const scenes = JSON.parse(fs.readFileSync(path.join(__dirname, "script.json"), "utf8"));
process.chdir("C:/Android/video");
const OUT = "v2";
for (const d of [OUT, `${OUT}/seg`, `${OUT}/txt`]) fs.mkdirSync(d, { recursive: true });

const FF = "C:/Android/ffmpeg/ffmpeg.exe";
const FP = "C:/Android/ffmpeg/ffprobe.exe";
const run = (args) => execFileSync(FF, ["-v", "error", "-y", ...args], { stdio: "inherit", maxBuffer: 1 << 26 });
const dur = (f) => parseFloat(execFileSync(FP, ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f]).toString());

const FONT_B = "C\\:/Windows/Fonts/malgunbd.ttf";
const FONT = "C\\:/Windows/Fonts/malgun.ttf";
const PINK = "0xFF6B8A";

// 화면 배치: 에뮬레이터 화면(16:9)을 가운데 위쪽에 두고, 아래 띠에 자막
const SW = 1504, SH = 846, SX = 208, SY = 88;
const XF = 0.5;          // 장면 전환(교차 페이드) 길이
const NARR_AT = XF + 0.25; // 장면 시작 후 내레이션 시작
const TAIL = 0.6;
const ZR = 0.8;          // 확대/축소에 걸리는 시간

// ---------- 공용 그림 (배경, 화면 마스크) ----------
function roundMask(file, w, h, r) {
  const e = `clip((${r}+0.5-hypot(max(abs(X-W/2)-(W/2-${r}),0),max(abs(Y-H/2)-(H/2-${r}),0)))*255,0,255)`;
  run(["-f", "lavfi", "-i", `color=black:s=${w}x${h}`, "-vf", `format=gray,geq=lum='${e}'`, "-frames:v", "1", file]);
}
function buildAssets() {
  roundMask(`${OUT}/mask_screen.png`, SW, SH, 26);
  roundMask(`${OUT}/mask_border.png`, SW + 4, SH + 4, 28);
  const P = "exp(-((X-260)*(X-260)+(Y-120)*(Y-120))/(2*560*560))";
  const B = "exp(-((X-1720)*(X-1720)+(Y-1000)*(Y-1000))/(2*640*640))";
  run(["-f", "lavfi", "-i", "color=black:s=1920x1080", "-vf",
    `format=gbrp,geq=r='12+40*${P}+6*${B}':g='15+14*${P}+16*${B}':b='20+22*${P}+38*${B}',format=yuv444p,noise=alls=2,vignette=PI/5,format=rgb24`,
    "-frames:v", "1", `${OUT}/bg_plain.png`]);
  // 화면 그림자 + 얇은 테두리를 미리 그려 둔 배경
  run(["-i", `${OUT}/bg_plain.png`, "-i", `${OUT}/mask_screen.png`, "-i", `${OUT}/mask_border.png`,
    "-f", "lavfi", "-i", `color=black:s=${SW}x${SH}`, "-f", "lavfi", "-i", `color=0x2E3541:s=${SW + 4}x${SH + 4}`,
    "-filter_complex",
    `[3:v][1:v]alphamerge,format=rgba,pad=${SW + 240}:${SH + 240}:120:120:color=black@0,gblur=sigma=34,colorchannelmixer=aa=0.7[sh];` +
    `[4:v][2:v]alphamerge[bd];` +
    `[0:v][sh]overlay=${SX - 120}:${SY + 22 - 120}[a];[a][bd]overlay=${SX - 2}:${SY - 2},format=rgb24`,
    "-frames:v", "1", `${OUT}/bg_screen.png`]);
}

// ---------- 글자 ----------
let txtN = 0;
const txt = (s) => { const f = `${OUT}/txt/t${txtN++}.txt`; fs.writeFileSync(f, s, "utf8"); return f; };
const ramp = (st, d) => `min(max((t-${st})/${d},0),1)`;
function text(s, { font = FONT, size, color = "0xF2F4F7", x, y, st = 0, d = 0.6, slide = 0 }) {
  const e = ramp(st, d);
  const yy = slide ? `${y}+${slide}*pow(1-${e},3)` : y;
  return `drawtext=fontfile='${font}':textfile='${txt(s)}':fontsize=${size}:fontcolor=${color}:x='${x}':y='${yy}':alpha='${e}'`;
}

// ---------- 확대 (zoompan, 2배 키운 뒤 잘라 떨림을 줄인다) ----------
function zoomExpr(zooms, D) {
  if (!zooms || !zooms.length) return { z: "1", x: "0", y: "0" };
  const ss = (v) => `(${v})*(${v})*(3-2*(${v}))`;
  const u = (a) => `clip((it-${a})/${ZR},0,1)`;
  const es = zooms.map((k) => {
    const b = k.b < 0 ? D + k.b : k.b;
    return { e: `(${ss(u(k.a))}-${ss(u(b))})`, k };
  });
  const z = "1" + es.map(({ e, k }) => `+${(k.z - 1).toFixed(3)}*${e}`).join("");
  const cx = "1920" + es.map(({ e, k }) => `+${2 * k.cx - 1920}*${e}`).join("");
  const cy = "1080" + es.map(({ e, k }) => `+${2 * k.cy - 1080}*${e}`).join("");
  return { z, x: `clip(${cx}-iw/zoom/2,0,iw-iw/zoom)`, y: `clip(${cy}-ih/zoom/2,0,ih-ih/zoom)` };
}

const enc = ["-c:v", "libx264", "-preset", "fast", "-crf", "17", "-pix_fmt", "yuv420p", "-r", "30"];

function renderClip(s, i, D, out) {
  const pad = Math.max(0, D - s.len);
  const { z, x, y } = zoomExpr(s.zoom, D);
  const num = String(i).padStart(2, "0");
  const labels = [
    text(num, { font: FONT_B, size: 30, color: PINK, x: `${SX}-30*pow(1-${ramp(0.45, 0.6)},3)`, y: 34, st: 0.45 }),
    text(s.label, { font: FONT_B, size: 30, x: `${SX + 56}-30*pow(1-${ramp(0.5, 0.6)},3)`, y: 34, st: 0.5 }),
    text("RecoDate Drive", { size: 24, color: "0x8A93A3", x: `${SX + SW}-text_w`, y: 39, st: 0, d: 0.01 }),
  ].join(",");
  run(["-ss", String(s.start), "-t", String(s.len), "-i", s.src,
    "-loop", "1", "-i", `${OUT}/bg_screen.png`, "-loop", "1", "-i", `${OUT}/mask_screen.png`,
    "-filter_complex",
    `[0:v]setpts=PTS-STARTPTS,fps=30,tpad=stop_mode=clone:stop_duration=${pad.toFixed(3)},scale=3840:2160:flags=bicubic,` +
    `zoompan=z='${z}':x='${x}':y='${y}':d=1:s=${SW}x${SH}:fps=30,format=rgba[scr];` +
    `[2:v]format=gray[m];[scr][m]alphamerge[sa];[1:v]format=rgba[bg];[bg][sa]overlay=${SX}:${SY}[c];[c]${labels},format=yuv420p[v]`,
    "-map", "[v]", "-t", D.toFixed(3), ...enc, out]);
}

function renderCard(kind, D, out) {
  const e = ramp(0.2, 0.8);
  const logoY = kind === "intro" ? "(h/2)-190" : "(h/2)-220";
  const barY = kind === "intro" ? "H/2-36" : "H/2-66";
  const parts = [
    text("RecoDate", { font: FONT_B, size: 140, color: PINK, x: "(w/2)-text_w-10", y: logoY, st: 0.2, d: 0.8, slide: 36 }),
    text("Drive", { font: FONT_B, size: 140, x: "(w/2)+14", y: logoY, st: 0.3, d: 0.8, slide: 36 }),
  ];
  if (kind === "intro") {
    parts.push(
      text("목적지만 정하면, 차를 댈 곳부터 코스까지", { size: 56, x: "(w-text_w)/2", y: "(h/2)+10", st: 1.0, slide: 16 }),
      text("Team DeltaLAB   ·   Pleos Connect 차량용 앱", { size: 30, color: "0x9AA3B2", x: "(w-text_w)/2", y: "(h/2)+110", st: 1.6 }),
    );
  } else {
    parts.push(
      text("검색도, 주차장 찾기도 필요 없는 나들이", { size: 54, x: "(w-text_w)/2", y: "(h/2)-20", st: 0.9, slide: 16 }),
      text("입력 없는 출발   ·   주차장부터 고르는 코스   ·   Pleos 내비 연동", { size: 32, color: "0x9AA3B2", x: "(w-text_w)/2", y: "(h/2)+80", st: 1.5 }),
      text("Team DeltaLAB", { font: FONT_B, size: 34, color: PINK, x: "(w-text_w)/2", y: "(h/2)+170", st: 2.1 }),
    );
  }
  run(["-loop", "1", "-i", `${OUT}/bg_plain.png`, "-f", "lavfi", "-i", `color=c=${PINK}:s=140x6:r=30`,
    "-filter_complex",
    `[0:v]format=rgba,${parts.join(",")}[base];[1:v]format=rgba,fade=t=in:st=0.8:d=0.6:alpha=1[bar];` +
    `[base][bar]overlay=x='(W-w)/2':y=${barY}:shortest=0,format=yuv420p[v]`,
    "-map", "[v]", "-t", D.toFixed(3), ...enc, out]);
  void e;
}

// ---------- 배경음: 부드러운 패드 화음, 내레이션이 나오면 자동으로 작아짐 ----------
function buildBgm(total) {
  const chords = [
    [130.81, 164.81, 196.0, 246.94], // Cmaj7
    [110.0, 130.81, 164.81, 196.0],  // Am7
    [87.31, 110.0, 130.81, 164.81],  // Fmaj7
    [98.0, 123.47, 146.83, 164.81],  // G6
  ];
  const L = 5.5, X = 1.5;
  const n = Math.ceil((total + 4) / (L - X));
  const src = [];
  for (let i = 0; i < n; i++) {
    const notes = chords[i % 4];
    const ex = notes.map((f) => `0.09*sin(2*PI*${f}*t)+0.05*sin(2*PI*${(f * 1.004).toFixed(3)}*t)+0.015*sin(2*PI*${2 * f}*t)`).join("+");
    src.push(`aevalsrc='${ex}':s=48000:d=${L},afade=t=in:d=1.4,afade=t=out:st=${L - 1.6}:d=1.6[c${i}]`);
  }
  let chain = "", last = "c0";
  for (let i = 1; i < n; i++) { chain += `[${last}][c${i}]acrossfade=d=${X}:c1=tri:c2=tri[m${i}];`; last = `m${i}`; }
  run(["-filter_complex",
    `${src.join(";")};${chain}[${last}]lowpass=f=1500,aecho=0.8:0.6:180|360:0.22|0.12,pan=stereo|c0=c0|c1=c0,` +
    `atrim=0:${total.toFixed(3)},loudnorm=I=-31:TP=-8:LRA=7[a]`,
    "-map", "[a]", "-ar", "48000", `${OUT}/bgm.wav`]);
}

// ---------- 자막 ----------
const splitSentences = (t) => t.match(/[^.?!]+[.?!]?/g).map((s) => s.trim()).filter(Boolean);
function wrap(s, max = 34) {
  if (s.length <= max) return [s];
  const mid = Math.floor(s.length / 2);
  let best = -1;
  for (let i = 0; i < s.length; i++) if (s[i] === " " && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid))) best = i;
  return best < 0 ? [s] : [s.slice(0, best), s.slice(best + 1)];
}
const srtT = (t) => { const ms = Math.round(t * 1000); const p = (v, n = 2) => String(v).padStart(n, "0");
  return `${p(Math.floor(ms / 3600000))}:${p(Math.floor(ms / 60000) % 60)}:${p(Math.floor(ms / 1000) % 60)},${p(ms % 1000, 3)}`; };
const assT = (t) => { const cs = Math.round(t * 100); const p = (v) => String(v).padStart(2, "0");
  return `${Math.floor(cs / 360000)}:${p(Math.floor(cs / 6000) % 60)}:${p(Math.floor(cs / 100) % 60)}.${p(cs % 100)}`; };

// ---------- 조립 ----------
if (!process.argv.includes("--skip-assets")) buildAssets();

let S = 0;
const plan = scenes.map((s, i) => {
  const narr = dur(`tts/${s.id}.wav`);
  const at = s.narrAt ?? NARR_AT;
  const D = Math.max(s.len, at + narr + TAIL);
  const p = { s, i, narr, at, D, start: S, seg: `${OUT}/seg/${String(i).padStart(2, "0")}_${s.id}.mp4` };
  S += D - XF;
  return p;
});
const total = plan[plan.length - 1].start + plan[plan.length - 1].D;

for (const p of plan) {
  if (process.argv.includes("--only") && !process.argv.includes(p.s.id)) continue;
  if (p.s.src.startsWith("card:")) renderCard(p.s.src.slice(5), p.D, p.seg);
  else renderClip(p.s, p.i, p.D, p.seg);
  console.log(`${p.s.id}: start ${p.start.toFixed(2)}s, length ${p.D.toFixed(2)}s, narration ${p.narr.toFixed(2)}s`);
}

// 자막 파일
const srt = [], ass = [];
for (const p of plan) {
  const sentences = splitSentences(p.s.text);
  const totalLen = sentences.reduce((a, x) => a + x.length, 0);
  let cur = p.start + p.at;
  for (const sen of sentences) {
    const d = p.narr * sen.length / totalLen;
    const lines = wrap(sen);
    srt.push(`${srt.length + 1}\n${srtT(cur)} --> ${srtT(cur + d - 0.05)}\n${lines.join("\n")}\n`);
    ass.push(`Dialogue: 0,${assT(cur)},${assT(cur + d - 0.05)},Main,,0,0,0,,{\\fad(120,120)}${lines.join("\\N")}`);
    cur += d;
  }
  for (const n of p.s.notes || []) ass.push(`Dialogue: 0,${assT(p.start + n.a)},${assT(p.start + n.b)},Note,,0,0,0,,{\\fad(200,200)}${n.text}`);
}
fs.writeFileSync(`${OUT}/sub.srt`, srt.join("\n"), "utf8");
fs.writeFileSync(`${OUT}/sub.ass`, [
  "[Script Info]", "ScriptType: v4.00+", "PlayResX: 1920", "PlayResY: 1080", "WrapStyle: 2", "ScaledBorderAndShadow: yes", "",
  "[V4+ Styles]",
  "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
  "Style: Main,Malgun Gothic,38,&H00FFFFFF,&H00FFFFFF,&H00000000,&H90000000,-1,0,0,0,100,100,0,0,1,0,2,2,120,120,34,1",
  "Style: Note,Malgun Gothic,32,&H00BAACA3,&H00BAACA3,&H00000000,&H90000000,0,0,0,0,100,100,0,0,1,0,2,2,120,120,40,1",
  "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text", ...ass, "",
].join("\n"), "utf8");

buildBgm(total);

// 장면 이어 붙이기 + 오디오 섞기 + 자막
const inputs = [];
plan.forEach((p) => inputs.push("-i", p.seg));
plan.forEach((p) => inputs.push("-i", `tts/${p.s.id}.wav`));
inputs.push("-i", `${OUT}/bgm.wav`);
const N = plan.length;
let vf = "", last = "0:v";
for (let i = 1; i < N; i++) {
  vf += `[${last}][${i}:v]xfade=transition=fade:duration=${XF}:offset=${plan[i].start.toFixed(3)}[x${i}];`;
  last = `x${i}`;
}
vf += `[${last}]fade=t=in:st=0:d=0.5,fade=t=out:st=${(total - 1.0).toFixed(3)}:d=1.0,subtitles=${OUT}/sub.ass[v];`;
const af = plan.map((p, i) => {
  const ms = Math.round((p.start + p.at) * 1000);
  return `[${N + i}:a]aformat=sample_rates=48000:channel_layouts=stereo,adelay=${ms}|${ms}[n${i}]`;
}).join(";") +
  `;${plan.map((_, i) => `[n${i}]`).join("")}amix=inputs=${N}:normalize=0:dropout_transition=0[narr];[narr]asplit[na][nk];` +
  `[${2 * N}:a]aformat=sample_rates=48000:channel_layouts=stereo[bg];` +
  `[bg][nk]sidechaincompress=threshold=0.012:ratio=6:attack=30:release=900:makeup=1[bgd];` +
  `[na][bgd]amix=inputs=2:normalize=0:duration=longest,volume=5.5dB,apad,atrim=0:${total.toFixed(3)},afade=t=out:st=${(total - 1.2).toFixed(3)}:d=1.2[a]`;
run([...inputs, "-filter_complex", vf + af, "-map", "[v]", "-map", "[a]",
  "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30",
  "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-t", total.toFixed(3), `${OUT}/RecoDate_Drive_demo.mp4`]);
console.log(`total ${total.toFixed(1)}s -> ${OUT}/RecoDate_Drive_demo.mp4`);
