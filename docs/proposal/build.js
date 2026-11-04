// 공식 접수양식(docx)의 "02 앱 프로젝트 소개" 입력칸 8개를 content.js 내용으로 채운다.
// 사용: node build.js <원본 양식 폴더(압축 푼 것)> <이미지 폴더> <출력 폴더>
// 01 참가신청서·03 동의서·04 서명란(개인정보)은 건드리지 않는다. 앱명 칸만 채운다.
const fs = require("fs");
const path = require("path");
const { APP, items, APPLICANT } = require("./content.js");

const [, , srcDir, imgDir, outDir] = process.argv;
fs.cpSync(srcDir, outDir, { recursive: true });
const docPath = path.join(outDir, "word/document.xml");
let xml = fs.readFileSync(docPath, "utf8");

const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const COLOR = "16324F";
const BODY = 20; // 10pt

// **굵게** 를 run 들로
function runs(text, { size = BODY, color, bold = false, italic = false } = {}) {
  const out = [];
  const parts = text.split(/(\*\*[^*]+\*\*)/);
  for (const part of parts) {
    if (!part) continue;
    const b = bold || part.startsWith("**");
    const t = part.startsWith("**") ? part.slice(2, -2) : part;
    const lines = t.split("\n");
    lines.forEach((line, i) => {
      const rpr = `<w:rPr>${b ? "<w:b/><w:bCs/>" : ""}${italic ? "<w:i/><w:iCs/>" : ""}${color ? `<w:color w:val="${color}"/>` : ""}<w:sz w:val="${size}"/><w:szCs w:val="${size}"/></w:rPr>`;
      if (i > 0) out.push(`<w:r>${rpr}<w:br/></w:r>`);
      if (line) out.push(`<w:r>${rpr}<w:t xml:space="preserve">${esc(line)}</w:t></w:r>`);
    });
  }
  return out.join("");
}

function para(inner, { before = 0, after = 60, ind = "", keepNext = false, numId } = {}) {
  const num = numId ? `<w:numPr><w:ilvl w:val="0"/><w:numId w:val="${numId}"/></w:numPr>` : "";
  return `<w:p><w:pPr>${keepNext ? "<w:keepNext/>" : ""}${num}<w:spacing w:before="${before}" w:after="${after}" w:line="276" w:lineRule="auto"/>${ind}</w:pPr>${inner}</w:p>`;
}

const BULLET_NUM = 90;

function table(widths, rows) {
  const total = widths.reduce((a, b) => a + b, 0);
  const border = `<w:top w:val="single" w:sz="4" w:space="0" w:color="B7C2CE"/><w:left w:val="single" w:sz="4" w:space="0" w:color="B7C2CE"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="B7C2CE"/><w:right w:val="single" w:sz="4" w:space="0" w:color="B7C2CE"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="B7C2CE"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="B7C2CE"/>`;
  const trs = rows.map((cells, r) => {
    const tcs = cells.map((c, i) => {
      const shade = r === 0 ? `<w:shd w:val="clear" w:color="auto" w:fill="E8EEF4"/>` : "";
      const content = c.split("\n").map((line) =>
        para(runs(line, { size: 18, bold: r === 0, color: r === 0 ? COLOR : undefined }), { after: 20 })).join("");
      return `<w:tc><w:tcPr><w:tcW w:w="${widths[i]}" w:type="dxa"/>${shade}<w:tcMar><w:top w:w="60" w:type="dxa"/><w:left w:w="80" w:type="dxa"/><w:bottom w:w="60" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tcMar></w:tcPr>${content}</w:tc>`;
    }).join("");
    return `<w:tr>${r === 0 ? "<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>" : "<w:trPr><w:cantSplit/></w:trPr>"}${tcs}</w:tr>`;
  }).join("");
  const grid = widths.map((w) => `<w:gridCol w:w="${w}"/>`).join("");
  // 셀 안의 표 뒤에는 문단이 와야 한다
  return `<w:tbl><w:tblPr><w:tblW w:w="${total}" w:type="dxa"/><w:tblBorders>${border}</w:tblBorders><w:tblLayout w:type="fixed"/><w:tblLook w:val="04A0" w:firstRow="1" w:lastRow="0" w:firstColumn="0" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/></w:tblPr><w:tblGrid>${grid}</w:tblGrid>${trs}</w:tbl>` + para("", { after: 40 });
}

// 이미지
const media = [];
let rid = 900;
let docPr = 900;
function image(file, caption) {
  const id = `rId${rid++}`;
  const name = `rd_${file}`;
  media.push({ id, file, name });
  const cx = Math.round(5.6 * 914400);
  const cy = Math.round(cx * 766 / 1050);
  const n = docPr++;
  const drawing = `<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="${cx}" cy="${cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:docPr id="${n}" name="${esc(name)}"/><wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" noChangeAspect="1"/></wp:cNvGraphicFramePr><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="${n}" name="${esc(name)}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="${id}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>`;
  return para(drawing, { before: 80, after: 20 }) +
    para(runs(caption, { size: 18, color: "4A5A6A" }), { after: 120 });
}

function block([kind, a, b]) {
  switch (kind) {
    case "h": return para(runs(a, { size: 21, bold: true, color: COLOR }), { before: 120, after: 60 });
    case "p": return para(runs(a), { after: 80 });
    case "b": return para(runs(a), { after: 40, numId: BULLET_NUM });
    case "note": return para(runs(a, { size: 17, color: "5A6B7B", italic: true }), { before: 40, after: 60 });
    case "t": return table(a, b);
    case "img": return image(a, b);
    default: throw new Error("unknown block " + kind);
  }
}

// 1) 02의 입력칸 8개: 입력칸(sdt)을 담은 문단을 생성한 문단들로 바꾼다
const sdtParas = [...xml.matchAll(/<w:p\b[^>]*>(?:(?!<w:p\b)[\s\S])*?<w:sdt>[\s\S]*?<\/w:sdt>[\s\S]*?<\/w:p>/g)];
if (sdtParas.length !== 8) throw new Error("expected 8 input boxes, found " + sdtParas.length);
for (let i = sdtParas.length - 1; i >= 0; i--) {
  const m = sdtParas[i];
  const generated = items[i].map(block).join("");
  let head = xml.slice(0, m.index);
  // 입력칸 행은 "페이지에서 나누지 않음"이라 긴 내용이 통째로 다음 쪽으로 밀린다 → 이 칸이 든 행만 나눌 수 있게
  const tr = head.lastIndexOf("<w:trPr>");
  const trEnd = head.indexOf("</w:trPr>", tr);
  if (tr < 0 || trEnd < 0) throw new Error("row of input box " + (i + 1) + " not found");
  head = head.slice(0, tr) + head.slice(tr, trEnd).replace("<w:cantSplit/>", "") + head.slice(trEnd);
  xml = head + generated + xml.slice(m.index + m[0].length);
}

// 2) "앱명 / 프로젝트명: ____" 칸 채우기 (01, 02, 04 세 곳)
xml = xml.replace(/<w:p\b[^>]*>(?:(?!<\/w:p>)[\s\S])*?프로젝트명:(?:(?!<\/w:p>)[\s\S])*?<\/w:p>/g, (p) => {
  let done = false;
  return p.replace(/<w:t( xml:space="preserve")?>([^<]*)<\/w:t>/g, (whole, sp, text) => {
    if (text.includes("프로젝트명:")) { done = true; return `<w:t xml:space="preserve">${text.replace(/_+/g, "")} ${esc(APP)}</w:t>`; }
    if (done && /^_+$/.test(text)) return `<w:t></w:t>`;
    return whole;
  });
});

// 2-1) 01 참가신청서: 확정된 값만 채운다 (개인 참가, 성명, 인원). 부문·연락처·서명은 손으로 쓴다.
if (APPLICANT) {
  // label이 들어 있는 run의 밑줄을 value로 바꾸고, 다음 label(":" 포함) 전까지 이어지는 밑줄 run은 비운다
  const fill = (label, value) => {
    const lit = label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const re = new RegExp(`<w:p\\b[^>]*>(?:(?!<\\/w:p>)[\\s\\S])*?${lit}(?:(?!<\\/w:p>)[\\s\\S])*?<\\/w:p>`);
    xml = xml.replace(re, (p) => {
      let state = 0; // 0: 찾는 중, 1: 채운 뒤 밑줄 비우는 중, 2: 끝
      return p.replace(/<w:t( xml:space="preserve")?>([^<]*)<\/w:t>/g, (whole, sp, text) => {
        if (state === 0 && text.includes(label)) {
          state = 1;
          return `<w:t xml:space="preserve">${text.replace(/_+/, " " + esc(value) + " ").replace(/_+/g, "")}</w:t>`;
        }
        if (state === 1) {
          if (text.includes(":")) { state = 2; return whole; }
          if (/_/.test(text)) return `<w:t xml:space="preserve">${text.replace(/_+/g, "")}</w:t>`;
        }
        return whole;
      });
    });
  };
  const team = APPLICANT.members.length > 0;
  const everyone = [APPLICANT.leader, ...APPLICANT.members];
  xml = team
    ? xml.replace(/참가 단위: □ 개인(\s*<\/w:t>[\s\S]*?)□ 팀/, "참가 단위: □ 개인$1■ 팀")
    : xml.replace("참가 단위: □ 개인", "참가 단위: ■ 개인");
  fill("팀명(개인 참가 시 성명):", APPLICANT.teamName);
  fill("대표자 성명:", APPLICANT.leader.name);
  fill("역할:", APPLICANT.leader.role);
  fill("참가 인원:", String(everyone.length));
  // 04 하단 "팀명(팀 참가 시):" — 양식에서 "팀명(" 과 "팀 참가 시): ___" 두 run으로 나뉘어 있다
  if (team) fill("팀 참가 시):", APPLICANT.teamName);

  // 표의 (행, 열) 칸 첫 문단 끝에 글자를 넣는다. headerText로 표를 찾는다
  const fillTable = (headerText, cells) => {
    xml = xml.replace(/<w:tbl>[\s\S]*?<\/w:tbl>/g, (tbl) => {
      if (!tbl.includes(headerText)) return tbl;
      let r = -1;
      return tbl.replace(/<w:tr\b[\s\S]*?<\/w:tr>/g, (tr) => {
        r++;
        let c = -1;
        return tr.replace(/<w:tc>[\s\S]*?<\/w:tc>/g, (tc) => {
          c++;
          const v = cells[`${r},${c}`];
          if (!v) return tc;
          return tc.replace(/<\/w:p>/, `<w:r><w:rPr><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr><w:t xml:space="preserve">${esc(v)}</w:t></w:r></w:p>`);
        });
      });
    });
  };
  // B 참가 구성원: 성명(0열)과 팀장 역할(4열)만. 소속·연락처·팀원 역할은 직접 기입
  const memberCells = {};
  everyone.forEach((p, i) => {
    memberCells[`${i + 1},0`] = p.name;
    if (p.role) memberCells[`${i + 1},4`] = p.role;
  });
  fillTable("담당 역할", memberCells);
  // 04 서명란: 성명(1열)만. 동의 여부·서명·날짜는 본인이 직접
  const signCells = {};
  everyone.forEach((p, i) => { signCells[`${i + 1},1`] = p.name; });
  fillTable("본인 서명", signCells);
}

// 3) 문서 루트에 그림용 네임스페이스가 없으면 추가
for (const [p, uri] of [
  ["wp", "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"],
  ["r", "http://schemas.openxmlformats.org/officeDocument/2006/relationships"],
]) {
  if (!new RegExp(`xmlns:${p}=`).test(xml.slice(0, 3000))) xml = xml.replace("<w:document ", `<w:document xmlns:${p}="${uri}" `);
}
fs.writeFileSync(docPath, xml);

// 4) 글머리 기호 번호 정의 추가
const numPath = path.join(outDir, "word/numbering.xml");
let num = fs.readFileSync(numPath, "utf8");
const abs = `<w:abstractNum w:abstractNumId="${BULLET_NUM}"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="320" w:hanging="220"/></w:pPr><w:rPr><w:color w:val="${COLOR}"/></w:rPr></w:lvl></w:abstractNum>`;
const firstNum = num.indexOf("<w:num ");
num = num.slice(0, firstNum) + abs + num.slice(firstNum);
num = num.replace("</w:numbering>", `<w:num w:numId="${BULLET_NUM}"><w:abstractNumId w:val="${BULLET_NUM}"/></w:num></w:numbering>`);
fs.writeFileSync(numPath, num);

// 5) 이미지 파일·관계·콘텐츠 형식
fs.mkdirSync(path.join(outDir, "word/media"), { recursive: true });
const relPath = path.join(outDir, "word/_rels/document.xml.rels");
let rels = fs.readFileSync(relPath, "utf8");
for (const m of media) {
  fs.copyFileSync(path.join(imgDir, m.file), path.join(outDir, "word/media", m.name));
  rels = rels.replace("</Relationships>", `<Relationship Id="${m.id}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${m.name}"/></Relationships>`);
}
fs.writeFileSync(relPath, rels);
const ctPath = path.join(outDir, "[Content_Types].xml");
let ct = fs.readFileSync(ctPath, "utf8");
if (!/Extension="jpg"/i.test(ct)) ct = ct.replace("<Default ", `<Default Extension="jpg" ContentType="image/jpeg"/><Default `);
fs.writeFileSync(ctPath, ct);

console.log(`filled 8 boxes, ${media.length} images`);
