import { execFileSync } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";

const readGitPaths = (args) =>
  execFileSync("git", args, { encoding: "utf8" }).split("\0").filter(Boolean);

const files = [
  ...new Set([
    ...readGitPaths(["ls-files", "-z"]),
    ...readGitPaths([
      "ls-files",
      "--others",
      "--exclude-standard",
      "-z",
      "--",
      "*.md",
    ]),
  ]),
]
  .filter((file) => /\.md$/i.test(file))
  .sort();

if (files.length === 0) {
  throw new Error("No tracked Markdown files found");
}

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

const formatOption = process.argv.indexOf("--format-changed");
if (formatOption !== -1) {
  const base = process.argv[formatOption + 1];
  if (!base) {
    throw new Error("--format-changed requires a base Git revision");
  }

  const changedFiles = [
    ...new Set([
      ...readGitPaths([
        "diff",
        "--name-only",
        "--diff-filter=ACMR",
        "-z",
        `${base}...HEAD`,
        "--",
        "*.md",
      ]),
      ...readGitPaths([
        "diff",
        "--name-only",
        "--diff-filter=ACMR",
        "-z",
        "--",
        "*.md",
      ]),
      ...readGitPaths([
        "ls-files",
        "--others",
        "--exclude-standard",
        "-z",
        "--",
        "*.md",
      ]),
    ]),
  ].sort();

  if (changedFiles.length > 0) {
    const prettier = "devmate-web/node_modules/prettier/bin/prettier.cjs";
    if (!existsSync(prettier)) {
      throw new Error(
        "Prettier is unavailable; run npm ci in devmate-web first",
      );
    }
    execFileSync(process.execPath, [prettier, "--check", ...changedFiles], {
      stdio: "inherit",
    });
  }
}
