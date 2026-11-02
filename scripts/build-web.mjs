import { build } from 'esbuild';
import { copyFile, mkdir, readFile, writeFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const outdir = path.join(root, 'app/src/main/assets/ui');
await mkdir(outdir, { recursive: true });
const result = await build({
  absWorkingDir: root,
  entryPoints: ['web/app.js'],
  outdir,
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['chrome80'],
  legalComments: 'eof',
  metafile: true,
  loader: { '.png': 'dataurl', '.woff2': 'file', '.woff': 'file' },
});
await copyFile(path.join(root, 'web/index.html'), path.join(outdir, 'index.html'));
const packages = new Set(Object.keys(result.metafile.inputs).filter(file => file.startsWith('node_modules/')).map(file => {
  const parts = file.split('/');
  return parts.slice(0, parts[1].startsWith('@') ? 3 : 2).join('/');
}));
const notices = [];
for (const directory of [...packages].sort()) {
  const info = JSON.parse(await readFile(path.join(root, directory, 'package.json'), 'utf8'));
  let license = '';
  for (const filename of ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'LICENSE.TXT', 'license']) {
    try { license = await readFile(path.join(root, directory, filename), 'utf8'); break; } catch { /* Try the next conventional name. */ }
  }
  notices.push(`${info.name} ${info.version} (${info.license || 'see package license'})\n${license}`);
}
await writeFile(path.join(outdir, 'third-party-notices.txt'), notices.join('\n\n' + '='.repeat(72) + '\n\n'));
const size = await stat(path.join(outdir, 'app.js'));
console.log(`Local MDUI + Leaflet UI built (${Math.round(size.size / 1024)} KB JavaScript).`);
