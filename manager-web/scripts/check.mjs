import {readdirSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const publicDirectory = new URL('../public/', import.meta.url);
for (const file of readdirSync(publicDirectory).filter(name => name.endsWith('.js'))) {
  const checked = spawnSync(process.execPath, ['--check', fileURLToPath(new URL(file,publicDirectory))], {stdio:'inherit'});
  if (checked.error) throw checked.error;
  if (checked.status !== 0) process.exit(checked.status ?? 1);
}
