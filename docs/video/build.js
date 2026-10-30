// 시연 영상 합치기: 장면 클립 + 한국어 TTS + 자막 (ffmpeg)
// 사용: node build.js  (작업 폴더 C:\Android\video)
const fs = require("fs");
const { execFileSync } = require("child_process");
const path = require("path");

process.chdir("C:/Android/video");
const FF = "C:/Android/ffmpeg/ffmpeg.exe";
const FP = "C:/Android/ffmpeg/ffprobe.exe";
const scenes = JSON.parse(fs.readFileSync("script.json", "utf8"));
fs.mkdirSync("seg", { recursive: true });
fs.mkdirSync("txt", { recursive: true });

const run = (args) => execFileSync(FF, ["-v", "error", "-y", ...args], { stdio: "inherit" });
const dur = (f) => parseFloat(execFileSync(FP, ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f]).toString());

const FONT_B = "C\\:/Windows/Fonts/malgunbd.ttf";
const FONT = "C\\:/Windows/Fonts/malgun.ttf";
const LEAD = 0.35; // 장면 시작 후 내레이션까지 여유
const TAIL = 0.7;  // 내레이션 뒤 여유

function cardFilter(kind, D) {
  const t = (name, text) => { fs.writeFileSync(`txt/${kind}_${name}.txt`, text, "utf8"); return `txt/${kind}_${name}.txt`; };
  const lines = kind === "intro"
    ? [
        [t("a", "RecoDate"), FONT_B, 120, "0xFF6B8A", "(w/2)-text_w-8", "(h/2)-150"],
        [t("b", "Drive"), FONT_B, 120, "white", "(w/2)+12", "(h/2)-150"],
        [t("c", "목적지만 정하면, 차를 댈 곳부터 코스까지"), FONT, 54, "0xF2F4F7", "(w-text_w)/2", "(h/2)+20"],
        [t("d", "Pleos Connect 차량용 앱 · 현대자동차그룹 차량용 앱 개발 공모전 출품작"), FONT, 32, "0xA3ACBA", "(w-text_w)/2", "(h/2)+120"],
      ]
    : [
        [t("a", "RecoDate"), FONT_B, 110, "0xFF6B8A", "(w/2)-text_w-8", "(h/2)-110"],
        [t("b", "Drive"), FONT_B, 110, "white", "(w/2)+12", "(h/2)-110"],
        [t("c", "검색도, 주차장 찾기도 필요 없는 나들이"), FONT, 54, "0xF2F4F7", "(w-text_w)/2", "(h/2)+50"],
      ];
  const dt = lines.map(([file, font, size, color, x, y]) =>
    `drawtext=fontfile='${font}':textfile='${file}':fontsize=${size}:fontcolor=${color}:x=${x}:y=${y}`).join(",");
  return `${dt},fade=t=in:st=0:d=0.6,fade=t=out:st=${(D - 0.6).toFixed(2)}:d=0.6`;
}

// 자막: 문장 단위로 나누고, 내레이션 길이 안에서 글자 수 비율로 시간 배분
function splitSentences(text) {
  return text.match(/[^.?!]+[.?!]?/g).map((s) => s.trim()).filter(Boolean);
}
function wrap(s, max = 34) {
  if (s.length <= max) return s;
  const mid = Math.floor(s.length / 2);
  let best = -1;
  for (let i = 0; i < s.length; i++) if (s[i] === " " && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid))) best = i;
  return best < 0 ? s : s.slice(0, best) + "\n" + s.slice(best + 1);
}
const ts = (t) => {
  const ms = Math.round(t * 1000);
  const h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, sec = Math.floor(ms / 1000) % 60, r = ms % 1000;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")},${String(r).padStart(3, "0")}`;
};

let t0 = 0;
const srt = [];
const segs = [];
scenes.forEach((s, i) => {
  const wav = `tts/${s.id}.wav`;
  const narr = dur(wav);
  const D = Math.max(s.len, LEAD + narr + TAIL);
  const out = `seg/${String(i).padStart(2, "0")}_${s.id}.mp4`;
  const audio = `[1:a]adelay=${Math.round(LEAD * 1000)}|${Math.round(LEAD * 1000)},aformat=sample_rates=48000:channel_layouts=stereo,apad,atrim=0:${D.toFixed(3)}[a]`;
  if (s.src.startsWith("card:")) {
    run(["-f", "lavfi", "-i", `color=c=0x0E1116:s=1920x1080:r=30:d=${D.toFixed(3)}`, "-i", wav,
      "-filter_complex", `[0:v]${cardFilter(s.src.slice(5), D)},format=yuv420p[v];${audio}`,
      "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "160k", "-t", D.toFixed(3), out]);
  } else {
    const pad = Math.max(0, D - s.len);
    run(["-ss", String(s.start), "-t", String(s.len), "-i", s.src, "-i", wav,
      "-filter_complex", `[0:v]scale=1920:1080,fps=30,setsar=1,tpad=stop_mode=clone:stop_duration=${pad.toFixed(3)},format=yuv420p[v];${audio}`,
      "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "160k", "-t", D.toFixed(3), out]);
  }
  segs.push(out);

  const sentences = splitSentences(s.text);
  const total = sentences.reduce((a, x) => a + x.length, 0);
  let cur = t0 + LEAD;
  for (const sen of sentences) {
    const d = narr * sen.length / total;
    srt.push(`${srt.length + 1}\n${ts(cur)} --> ${ts(cur + d - 0.05)}\n${wrap(sen)}\n`);
    cur += d;
  }
  console.log(`${s.id}: narration ${narr.toFixed(2)}s, scene ${D.toFixed(2)}s`);
  t0 += D;
});

fs.writeFileSync("sub.srt", srt.join("\n"), "utf8");
fs.writeFileSync("concat.txt", segs.map((f) => `file '${f}'`).join("\n"), "utf8");
run(["-f", "concat", "-safe", "0", "-i", "concat.txt", "-c", "copy", "full.mp4"]);
const style = "FontName=Malgun Gothic,FontSize=15,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BackColour=&H99000000,BorderStyle=4,Outline=0,Shadow=0,MarginV=22,Bold=1";
run(["-i", "full.mp4", "-vf", `subtitles=sub.srt:force_style='${style}'`, "-c:v", "libx264", "-preset", "medium", "-crf", "21", "-c:a", "copy", "-movflags", "+faststart", "RecoDate_Drive_demo.mp4"]);
console.log(`total ${t0.toFixed(1)}s -> RecoDate_Drive_demo.mp4`);
