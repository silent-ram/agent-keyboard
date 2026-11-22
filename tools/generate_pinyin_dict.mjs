#!/usr/bin/env node

/**
 * Build the bundled pinyin dictionary from CC-CEDICT, Jieba word frequencies,
 * and optional THUOCL domain frequencies. Inputs are build-time files; the
 * app stays offline.
 *
 * Output format: pinyin<TAB>word<TAB>weight
 */

import fs from "node:fs";
import path from "node:path";

const args = parseArgs(process.argv.slice(2));
const cedictPath = required(args, "cedict");
const jiebaPath = required(args, "jieba");
const outputPath = required(args, "output");
const thuoclDir = args.thuocl;
const limit = Number(args.limit ?? Number.MAX_SAFE_INTEGER);

if (!Number.isInteger(limit) || limit <= 0) {
  throw new Error("--limit must be a positive integer");
}

const jiebaFrequencyByWord = loadJiebaFrequencies(jiebaPath);
const thuoclFrequencyByWord = thuoclDir ? loadThuoclFrequencies(thuoclDir) : new Map();
const entries = loadCedict(cedictPath);
entries.forEach((entry) => {
  const jiebaFrequency = jiebaFrequencyByWord.get(entry.word) ?? 0;
  const thuoclFrequency = thuoclFrequencyByWord.get(entry.word) ?? 0;
  entry.weight = rankWeight(jiebaFrequency, thuoclFrequency);
  entry.hasJiebaFrequency = jiebaFrequency > 0;
  entry.hasThuoclFrequency = thuoclFrequency > 0;
});
const selected = selectEntries(entries, limit);

fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
fs.writeFileSync(
  outputPath,
  selected
    .map((entry) => `${entry.pinyin}\t${entry.word}\t${entry.weight}`)
    .join("\n") + "\n",
  "utf8",
);

console.log(
  JSON.stringify({
    jiebaWords: jiebaFrequencyByWord.size,
    thuoclWords: thuoclFrequencyByWord.size,
    matchedCedictEntries: entries.length,
    selectedEntries: selected.length,
    selectedWithJiebaFrequency: selected.filter((entry) => entry.hasJiebaFrequency).length,
    selectedWithThuoclFrequency: selected.filter((entry) => entry.hasThuoclFrequency).length,
    outputBytes: fs.statSync(outputPath).size,
    output: path.resolve(outputPath),
  }),
);

function parseArgs(rawArgs) {
  const result = {};
  for (let index = 0; index < rawArgs.length; index += 1) {
    const arg = rawArgs[index];
    if (!arg.startsWith("--")) {
      throw new Error(`Unexpected argument: ${arg}`);
    }
    const key = arg.slice(2);
    const value = rawArgs[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for --${key}`);
    }
    result[key] = value;
    index += 1;
  }
  return result;
}

function required(values, key) {
  if (!values[key]) {
    throw new Error(`Missing --${key}`);
  }
  return values[key];
}

function loadCedict(filePath) {
  const result = [];
  const seen = new Set();
  let sourceOrder = 0;
  for (const line of readLines(filePath)) {
    const normalizedLine = line.trim();
    if (!normalizedLine || normalizedLine.startsWith("#")) {
      continue;
    }
    const traditionalEnd = normalizedLine.search(/\s/);
    const simplifiedStart = normalizedLine.slice(traditionalEnd).search(/\S/) + traditionalEnd;
    const simplifiedEndRelative = normalizedLine.slice(simplifiedStart).search(/\s/);
    if (traditionalEnd < 0 || simplifiedStart < traditionalEnd || simplifiedEndRelative < 0) {
      continue;
    }
    const simplifiedEnd = simplifiedStart + simplifiedEndRelative;
    const word = normalizedLine.slice(simplifiedStart, simplifiedEnd);
    const pinyinStart = normalizedLine.indexOf("[", simplifiedEnd);
    const pinyinEnd = normalizedLine.indexOf("]", pinyinStart);
    if (pinyinStart < 0 || pinyinEnd <= pinyinStart) {
      continue;
    }
    const syllables = normalizedLine.slice(pinyinStart + 1, pinyinEnd).trim().split(/\s+/);
    if (!isHanWord(word) || word.length < 2 || word.length > 12) {
      continue;
    }
    if (syllables.length < 2 || syllables.length > 14) {
      continue;
    }
    const pinyin = syllables.map(normalizeSyllable).join("");
    if (!/^[a-zv]+$/.test(pinyin)) {
      continue;
    }
    const key = `${pinyin}\t${word}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push({
      pinyin,
      word,
      weight: 0,
      hasJiebaFrequency: false,
      hasThuoclFrequency: false,
      sourceOrder: sourceOrder++,
    });
  }
  return result;
}

function loadThuoclFrequencies(directory) {
  const frequencies = new Map();
  for (const name of fs.readdirSync(directory).sort()) {
    const filePath = path.join(directory, name);
    if (!fs.statSync(filePath).isFile()) {
      continue;
    }
    for (const line of readLines(filePath)) {
      const fields = line.trim().split(/\s+/);
      if (fields.length < 2 || !isHanWord(fields[0])) {
        continue;
      }
      const frequency = Number(fields[1]);
      if (!Number.isSafeInteger(frequency) || frequency < 0) {
        continue;
      }
      frequencies.set(fields[0], Math.max(frequencies.get(fields[0]) ?? 0, frequency));
    }
  }
  return frequencies;
}

function loadJiebaFrequencies(filePath) {
  const frequencies = new Map();
  for (const line of readLines(filePath)) {
    const fields = line.trim().split(/\s+/);
    if (fields.length < 2 || !isHanWord(fields[0])) {
      continue;
    }
    const frequency = Number(fields[1]);
    if (!Number.isSafeInteger(frequency) || frequency < 0) {
      continue;
    }
    frequencies.set(fields[0], Math.max(frequencies.get(fields[0]) ?? 0, frequency));
  }
  return frequencies;
}

function rankWeight(jiebaFrequency, thuoclFrequency) {
  return Math.floor(
    Math.log2(jiebaFrequency + 1) * 1_000
      + Math.log2(thuoclFrequency + 1) * 100,
  );
}

function selectEntries(entries, limit) {
  return entries
    .sort((left, right) =>
      right.weight - left.weight
      || left.sourceOrder - right.sourceOrder
      || left.word.localeCompare(right.word),
    )
    .slice(0, limit);
}

function normalizeSyllable(value) {
  const normalized = value
    .toLowerCase()
    .replace(/[1-5]/g, "")
    .replaceAll("ǖ", "v")
    .replaceAll("ǘ", "v")
    .replaceAll("ǚ", "v")
    .replaceAll("ǜ", "v")
    .replaceAll("u:", "v")
    .replaceAll("ü", "v");
  return Array.from(normalized.normalize("NFD"))
    .filter((character) => {
      const codePoint = character.codePointAt(0);
      return codePoint < 0x0300 || codePoint > 0x036f;
    })
    .join("");
}

function isHanWord(word) {
  if (!word) {
    return false;
  }
  return Array.from(word).every((character) => {
    const codePoint = character.codePointAt(0);
    return (codePoint >= 0x3400 && codePoint <= 0x9fff)
      || (codePoint >= 0x20000 && codePoint <= 0x323af);
  });
}

function readLines(filePath) {
  return fs.readFileSync(filePath, "utf8").split(/\r?\n/);
}
