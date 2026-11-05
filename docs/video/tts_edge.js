// 장면별 내레이션을 Edge 신경망 한국어 음성으로 만든다 (무료, 키 불필요).
// 사용: node tts_edge.js [음성] [속도]   기본: ko-KR-SunHiNeural, -4%
// 필요: C:\Android\tts-edge 에 npm install msedge-tts@2
// 결과: C:\Android\video\tts_edge\<id>.wav (48kHz 모노)
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const { MsEdgeTTS, OUTPUT_FORMAT } = require("C:/Android/tts-edge/node_modules/msedge-tts");

const VOICE = process.argv[2] || "ko-KR-SunHiNeural";
const RATE = process.argv[3] || "-4%";
const scenes = JSON.parse(fs.readFileSync(path.join(__dirname, "script.json"), "utf8"));
const OUT = "C:/Android/video/tts_edge";
fs.mkdirSync(OUT, { recursive: true });

(async () => {
  for (const s of scenes) {
    const tts = new MsEdgeTTS();
    await tts.setMetadata(VOICE, OUTPUT_FORMAT.AUDIO_24KHZ_96KBITRATE_MONO_MP3);
    const dir = `${OUT}/_${s.id}`;
    fs.mkdirSync(dir, { recursive: true });
    const { audioFilePath } = await tts.toFile(dir, s.spoken, { rate: RATE });
    // 앞뒤 무음을 다듬고 48kHz wav로
    execFileSync("C:/Android/ffmpeg/ffmpeg.exe", ["-v", "error", "-y", "-i", audioFilePath,
      "-af", "silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse",
      "-ar", "48000", `${OUT}/${s.id}.wav`]);
    fs.rmSync(dir, { recursive: true, force: true });
    console.log(`${s.id} done`);
  }
})().catch((e) => { console.error(e); process.exit(1); });
