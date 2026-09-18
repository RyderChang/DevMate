import { readFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";

const files = [
  "README.md",
  "devmate-server/README.md",
  "devmate-web/README.md",
  "docs/README.md",
  "docs/development/local-development.md",
  "docs/testing/foundation-acceptance.md",
];
let links = 0;
for (const file of files) {
  const source = readFileSync(file, "utf8");
  if ((source.match(/^```/gm)?.length ?? 0) % 2 !== 0) {
    throw new Error(`Unbalanced code fences: ${file}`);
  }
  const prose = source.replace(/^```[^\n]*\n[\s\S]*?^```\s*$/gm, "");
  for (const match of prose.matchAll(/\]\(([^)]+)\)/g)) {
    const target = match[1];
    if (/^(https?:|mailto:|#)/.test(target)) continue;
    const path = resolve(
      dirname(file),
      decodeURIComponent(target.split("#")[0]),
    );
    if (!existsSync(path)) throw new Error(`Broken link: ${file} -> ${target}`);
    links++;
  }
}
console.log(
  `Checked ${files.length} Markdown files and ${links} relative links`,
);
