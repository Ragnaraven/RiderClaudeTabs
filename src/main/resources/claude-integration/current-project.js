// Resolves the current Rider project from process.cwd().
// Walks up looking for .idea/; falls back to cwd itself if no .idea/ ancestor exists.
// Mirrors ClaudeTabsHelpers.projectHashForPath() so the resulting `hash` matches the
// plugin's open-tabs-<hash>.json / user-closed-<hash>.json filenames exactly.
//
// Usage:   node current-project.js
// Output:  {"root":"/repos/MyApp","hash":"repos-MyApp","name":"MyApp"}
//
// Also require()d by recover-tabs.js — keep resolveProject() the single source of truth
// for the walk-up and the hash so the two can never disagree about a project identity.

const fs = require('fs');
const path = require('path');

function resolveProject(from) {
  let cur = from || process.cwd();
  let root = cur;
  while (true) {
    try {
      if (fs.existsSync(path.join(cur, '.idea'))) { root = cur; break; }
    } catch {}
    const parent = path.dirname(cur);
    if (parent === cur) break; // filesystem root
    cur = parent;
  }

  // Mirror Kotlin: basePath.replace("\\", "/").replace(":/", "--").replace("/", "-")
  const hash = root
    .replace(/\\/g, '/')
    .replace(/:\//, '--')
    .replace(/\//g, '-');

  return { root, hash, name: path.basename(root) };
}

module.exports = { resolveProject };

if (require.main === module) {
  console.log(JSON.stringify(resolveProject()));
}
