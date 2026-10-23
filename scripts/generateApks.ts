import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';

type ApkCopy = { source: string; destination: string };

const root = process.cwd();
const downloadsDir = path.join(root, 'public', 'downloads');
const apks: ApkCopy[] = [
  {
    source: path.join(root, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
    destination: path.join(downloadsDir, 'OpenKayak-WearOS.apk'),
  },
  {
    source: path.join(root, 'phone', 'build', 'outputs', 'apk', 'debug', 'phone-debug.apk'),
    destination: path.join(downloadsDir, 'OpenKayak-Mobile.apk'),
  },
];

/**
 * Copy only APKs produced by Android Gradle. Never manufacture a ZIP/HTML file
 * with an .apk extension; Android packages require compiled DEX, resources and
 * a binary manifest and must be built by the Android toolchain.
 */
function validateRealApk(filePath: string): void {
  if (!fs.existsSync(filePath)) {
    throw new Error(
      `Missing native APK: ${path.relative(root, filePath)}. Run ./gradlew --no-daemon :app:assembleDebug :phone:assembleDebug first.`,
    );
  }

  const stat = fs.statSync(filePath);
  if (!stat.isFile() || stat.size < 500_000) {
    throw new Error(`Refusing to publish a suspiciously small APK: ${path.relative(root, filePath)} (${stat.size} bytes).`);
  }

  const fd = fs.openSync(filePath, 'r');
  const signature = Buffer.alloc(4);
  try {
    fs.readSync(fd, signature, 0, signature.length, 0);
  } finally {
    fs.closeSync(fd);
  }
  if (signature.readUInt32LE(0) !== 0x04034b50) {
    throw new Error(`File is not a ZIP/APK archive: ${path.relative(root, filePath)}.`);
  }

  const entries = execFileSync('unzip', ['-Z1', filePath], { encoding: 'utf8' });
  if (!entries.split(/\r?\n/).includes('AndroidManifest.xml') || !entries.split(/\r?\n/).some((entry) => /^classes(\d*)?\.dex$/.test(entry))) {
    throw new Error(`Archive is missing Android manifest or compiled DEX: ${path.relative(root, filePath)}.`);
  }
}

function main(): void {
  for (const apk of apks) validateRealApk(apk.source);
  fs.mkdirSync(downloadsDir, { recursive: true });

  for (const apk of apks) {
    fs.copyFileSync(apk.source, apk.destination);
    const size = fs.statSync(apk.destination).size;
    console.log(`Copied verified Android APK: ${path.relative(root, apk.destination)} (${size} bytes)`);
  }
}

main();
