// 배경음 작곡: 차분한 퓨처팝·누디스코 느낌 (92BPM, Fmaj7-Em7-Dm7-Cmaj7)
// 일렉 피아노 + 패드 + 베이스 + 부드러운 킥/하이햇. 외부 음원 없이 직접 합성한다.
// 사용: node bgm.js <길이(초)> <출력.wav>
const fs = require("fs");

const LEN = parseFloat(process.argv[2] || "115");
const OUT = process.argv[3] || "C:/Android/video/v2/bgm_src.wav";
const SR = 44100;
const N = Math.ceil(LEN * SR);
const BPM = 92, BEAT = 60 / BPM, BAR = BEAT * 4;
const nBars = Math.ceil(LEN / BAR);

const L = new Float32Array(N), R = new Float32Array(N);       // 드라이
const SL = new Float32Array(N), SRb = new Float32Array(N);    // 리버브 보내기
const mf = (m) => 440 * Math.pow(2, (m - 69) / 12);
const chords = [[53, 57, 60, 64], [52, 55, 59, 62], [50, 53, 57, 60], [48, 52, 55, 59]];
const roots = [41, 40, 38, 36];
let seed = 7;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296) * 2 - 1;

// 구간: 처음 2마디는 피아노·패드만, 이후 베이스·하이햇, 4마디부터 킥, 마지막 3마디는 리듬 빠짐
const has = (bar, part) => {
  if (bar >= nBars - 3) return part === "keys" || part === "pad" || part === "bass";
  if (part === "bass" || part === "hat") return bar >= 2;
  if (part === "kick") return bar >= 4;
  return true;
};

function add(buf, start, len, fn, gain) {
  const s0 = Math.max(0, Math.floor(start * SR)), s1 = Math.min(N, Math.floor((start + len) * SR));
  for (let i = s0; i < s1; i++) buf[i] += gain * fn(i / SR - start);
}

for (let b = 0; b < nBars; b++) {
  const t0 = b * BAR, c = chords[b % 4], r = roots[b % 4];
  // 패드: 느린 어택, 다음 화음과 겹치며 이어짐
  if (has(b, "pad")) for (const m of c) {
    const f = mf(m);
    const env = (t) => Math.min(1, t / 0.8) * Math.min(1, Math.max(0, (BAR + 0.6 - t) / 0.8));
    const v = (t, d) => Math.sin(2 * Math.PI * f * d * t) + 0.25 * Math.sin(4 * Math.PI * f * d * t) + 0.08 * Math.sin(6 * Math.PI * f * d * t);
    add(L, t0, BAR + 0.6, (t) => env(t) * v(t, 0.9985), 0.016);
    add(R, t0, BAR + 0.6, (t) => env(t) * v(t, 1.0015), 0.016);
    add(SL, t0, BAR + 0.6, (t) => env(t) * v(t, 1), 0.012);
    add(SRb, t0, BAR + 0.6, (t) => env(t) * v(t, 1), 0.012);
  }
  // 일렉 피아노(FM): 1박, 2박 반, 4박(약하게) — 누디스코식 엇박
  if (has(b, "keys")) for (const [beat, vel] of [[0, 1], [1.5, 0.75], [3, 0.5]]) {
    c.forEach((m, k) => {
      const f = mf(m + 12), pan = 0.5 + (k - 1.5) * 0.12;
      const fn = (t) => {
        const idx = 1.2 * Math.exp(-t * 5);
        return Math.sin(2 * Math.PI * f * t + idx * Math.sin(2 * Math.PI * f * t)) * Math.exp(-t * 2.4) * Math.min(1, t * 300);
      };
      const g = 0.065 * vel;
      add(L, t0 + beat * BEAT, 2.2, fn, g * (1 - pan) * 2 * 0.7);
      add(R, t0 + beat * BEAT, 2.2, fn, g * pan * 2 * 0.7);
      add(SL, t0 + beat * BEAT, 2.2, fn, g * 0.5);
      add(SRb, t0 + beat * BEAT, 2.2, fn, g * 0.5);
    });
  }
  // 베이스: 1박, 2박 반, 3박 (짧고 둥글게)
  if (has(b, "bass")) for (const [beat, len, oct] of [[0, 1.3, 0], [1.5, 0.45, 12], [2, 1.6, 0]]) {
    const f = mf(r + oct), d = len * BEAT;
    const fn = (t) => (Math.sin(2 * Math.PI * f * t) + 0.18 * Math.sin(4 * Math.PI * f * t)) *
      Math.min(1, t * 150) * Math.min(1, Math.max(0, (d - t) / 0.06)) * (0.75 + 0.25 * Math.exp(-t * 6));
    add(L, t0 + beat * BEAT, d, fn, 0.04);
    add(R, t0 + beat * BEAT, d, fn, 0.04);
  }
  // 킥: 1박, 3박만 (차분하게)
  if (has(b, "kick")) for (const beat of [0, 2]) {
    const fn = (t) => Math.sin(2 * Math.PI * (48 * t + (70 / 28) * (1 - Math.exp(-28 * t)))) * Math.exp(-t * 14);
    add(L, t0 + beat * BEAT, 0.4, fn, 0.11);
    add(R, t0 + beat * BEAT, 0.4, fn, 0.11);
  }
  // 하이햇: 엇박 8분음표, 아주 작게
  if (has(b, "hat")) for (const beat of [0.5, 1.5, 2.5, 3.5]) {
    let prev = 0;
    const fn = (t) => { const x = rnd(); const y = x - prev; prev = x; return y * Math.exp(-t * 70); };
    const pan = beat % 2 === 0.5 ? 0.4 : 0.6;
    add(L, t0 + beat * BEAT, 0.08, fn, 0.012 * (1 - pan) * 2);
    add(R, t0 + beat * BEAT, 0.08, fn, 0.012 * pan * 2);
  }
}

// 리버브 (Schroeder: 콤 4개 + 올패스 2개)
function reverb(x, spread) {
  const out = new Float32Array(N);
  for (const [ms, fb] of [[29.7, 0.8], [37.1, 0.79], [41.1, 0.78], [43.7, 0.77]]) {
    const d = Math.round((ms + spread) * SR / 1000), buf = new Float32Array(d);
    let p = 0, lp = 0;
    for (let i = 0; i < N; i++) { const y = buf[p]; lp = y * 0.6 + lp * 0.4; buf[p] = x[i] + lp * fb; p = (p + 1) % d; out[i] += y * 0.25; }
  }
  for (const [ms, g] of [[5, 0.7], [1.7, 0.7]]) {
    const d = Math.round(ms * SR / 1000), buf = new Float32Array(d);
    let p = 0;
    for (let i = 0; i < N; i++) { const bv = buf[p]; const y = -g * out[i] + bv; buf[p] = out[i] + g * y; p = (p + 1) % d; out[i] = y; }
  }
  return out;
}
const rl = reverb(SL, 0), rr = reverb(SRb, 1.3);

// 섞고, 부드럽게 고역을 깎고, 처음과 끝을 페이드
const lpL = { v: 0 }, lpR = { v: 0 }, a = 1 - Math.exp(-2 * Math.PI * 5200 / SR);
let peak = 0;
for (let i = 0; i < N; i++) {
  const t = i / SR, fade = Math.min(1, t / 2.5) * Math.min(1, (LEN - t) / 4);
  lpL.v += a * (L[i] + rl[i] * 0.9 - lpL.v); lpR.v += a * (R[i] + rr[i] * 0.9 - lpR.v);
  L[i] = lpL.v * fade; R[i] = lpR.v * fade;
  peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
}
const norm = 0.85 / peak;
const data = Buffer.alloc(N * 4);
for (let i = 0; i < N; i++) {
  data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, L[i] * norm)) * 32767), i * 4);
  data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, R[i] * norm)) * 32767), i * 4 + 2);
}
const h = Buffer.alloc(44);
h.write("RIFF", 0); h.writeUInt32LE(36 + data.length, 4); h.write("WAVE", 8); h.write("fmt ", 12);
h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(2, 22); h.writeUInt32LE(SR, 24);
h.writeUInt32LE(SR * 4, 28); h.writeUInt16LE(4, 32); h.writeUInt16LE(16, 34); h.write("data", 36); h.writeUInt32LE(data.length, 40);
fs.writeFileSync(OUT, Buffer.concat([h, data]));
console.log(`bgm ${LEN}s, ${nBars} bars @ ${BPM}bpm -> ${OUT}`);
