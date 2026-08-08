import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve, sep } from "node:path";

const [bundlePath, destination] = process.argv.slice(2);
if (!bundlePath || !destination) {
  throw new Error("Usage: node extract_version_j_bundle.mjs <bundle.txt> <destination>");
}

const bundle = readFileSync(bundlePath, "utf8");
const destinationRoot = resolve(destination);
const beginPattern = /^===== BEGIN FILE: (.+) =====\r?\n/gm;
let match;
let extracted = 0;

while ((match = beginPattern.exec(bundle))) {
  const relativePath = match[1];
  const endMarker = `===== END FILE: ${relativePath} =====`;
  const endIndex = bundle.indexOf(endMarker, beginPattern.lastIndex);
  if (endIndex < 0) throw new Error(`Missing end marker for ${relativePath}`);

  const outputPath = resolve(destinationRoot, relativePath);
  if (outputPath !== destinationRoot && !outputPath.startsWith(destinationRoot + sep)) {
    throw new Error(`Unsafe bundle path: ${relativePath}`);
  }

  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, bundle.slice(beginPattern.lastIndex, endIndex));
  extracted++;
  beginPattern.lastIndex = endIndex + endMarker.length;
}

if (extracted !== 17) throw new Error(`Expected 17 files, extracted ${extracted}`);
process.stdout.write(`Extracted ${extracted} Version J files to ${destinationRoot}\n`);
