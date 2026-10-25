// Runs the backend's Gradle wrapper on any OS: gradlew.bat on Windows, ./gradlew elsewhere.
// Usage: node tools/gradle.mjs <tasks and options…>   (npm run start:backend, test:backend, build:jar)
import { spawnSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const backend = join(dirname(fileURLToPath(import.meta.url)), '..', 'backend');
const windows = process.platform === 'win32';
const result = spawnSync(windows ? `"${join(backend, 'gradlew.bat')}"` : './gradlew', process.argv.slice(2), { cwd: backend, stdio: 'inherit', shell: windows });
process.exit(result.status ?? 1);
