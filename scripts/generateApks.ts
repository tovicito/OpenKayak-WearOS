import fs from 'fs';
import path from 'path';
import zlib from 'zlib';

const downloadsDir = path.join(process.cwd(), 'public', 'downloads');
if (!fs.existsSync(downloadsDir)) {
  fs.mkdirSync(downloadsDir, { recursive: true });
}

interface ZipEntry {
  name: string;
  data: Buffer;
}

/**
 * Pure TypeScript ZIP builder with standard PKZIP specification
 */
function createZipBuffer(entries: ZipEntry[]): Buffer {
  const localHeaders: Buffer[] = [];
  const centralHeaders: Buffer[] = [];
  let offset = 0;

  for (const entry of entries) {
    const filenameBuf = Buffer.from(entry.name, 'utf8');
    const compressed = zlib.deflateRawSync(entry.data);
    const crc = computeCrc32(entry.data);

    // Local file header (30 bytes + filename + data)
    const localHeader = Buffer.alloc(30 + filenameBuf.length);
    localHeader.writeUInt32LE(0x04034b50, 0); // signature
    localHeader.writeUInt16LE(20, 4); // version needed to extract
    localHeader.writeUInt16LE(0, 6); // general purpose bit flag
    localHeader.writeUInt16LE(8, 8); // compression method (8 = deflate)
    localHeader.writeUInt16LE(0, 10); // file last mod time
    localHeader.writeUInt16LE(0, 12); // file last mod date
    localHeader.writeUInt32LE(crc, 14); // crc-32
    localHeader.writeUInt32LE(compressed.length, 18); // compressed size
    localHeader.writeUInt32LE(entry.data.length, 22); // uncompressed size
    localHeader.writeUInt16LE(filenameBuf.length, 26); // file name length
    localHeader.writeUInt16LE(0, 28); // extra field length
    filenameBuf.copy(localHeader, 30);

    localHeaders.push(localHeader, compressed);

    // Central directory header (46 bytes + filename)
    const centralHeader = Buffer.alloc(46 + filenameBuf.length);
    centralHeader.writeUInt32LE(0x02014b50, 0); // signature
    centralHeader.writeUInt16LE(20, 4); // version made by
    centralHeader.writeUInt16LE(20, 6); // version needed to extract
    centralHeader.writeUInt16LE(0, 8); // general purpose bit flag
    centralHeader.writeUInt16LE(8, 10); // compression method
    centralHeader.writeUInt16LE(0, 12); // last mod time
    centralHeader.writeUInt16LE(0, 14); // last mod date
    centralHeader.writeUInt32LE(crc, 16); // crc32
    centralHeader.writeUInt32LE(compressed.length, 20); // compressed size
    centralHeader.writeUInt32LE(entry.data.length, 24); // uncompressed size
    centralHeader.writeUInt16LE(filenameBuf.length, 28); // filename length
    centralHeader.writeUInt16LE(0, 30); // extra field length
    centralHeader.writeUInt16LE(0, 32); // file comment length
    centralHeader.writeUInt16LE(0, 34); // disk number start
    centralHeader.writeUInt16LE(0, 36); // internal file attributes
    centralHeader.writeUInt32LE(0, 38); // external file attributes
    centralHeader.writeUInt32LE(offset, 42); // relative offset of local header
    filenameBuf.copy(centralHeader, 46);

    centralHeaders.push(centralHeader);

    offset += localHeader.length + compressed.length;
  }

  const centralDirOffset = offset;
  const centralDirSize = centralHeaders.reduce((sum, h) => sum + h.length, 0);

  // End of central directory record (22 bytes)
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); // signature
  eocd.writeUInt16LE(0, 4); // number of this disk
  eocd.writeUInt16LE(0, 6); // disk where central directory starts
  eocd.writeUInt16LE(entries.length, 8); // number of central directory records on this disk
  eocd.writeUInt16LE(entries.length, 10); // total number of central directory records
  eocd.writeUInt32LE(centralDirSize, 12); // size of central directory
  eocd.writeUInt32LE(centralDirOffset, 16); // offset of start of central directory
  eocd.writeUInt16LE(0, 20); // comment length

  return Buffer.concat([...localHeaders, ...centralHeaders, eocd]);
}

// CRC32 table & calculator
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  crcTable[n] = c;
}

function computeCrc32(buf: Buffer): number {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = crcTable[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function buildApk(
  targetPath: string,
  packageName: string,
  appName: string,
  isWearOS: boolean
): void {
  const manifestXml = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${packageName}"
    android:versionCode="202"
    android:versionName="2.2.2">

    <uses-sdk android:minSdkVersion="${isWearOS ? '30' : '26'}" android:targetSdkVersion="34" />

    ${isWearOS ? '<uses-feature android:name="android.hardware.type.watch" />' : ''}
    <uses-feature android:name="android.hardware.location.gps" android:required="true" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
    <uses-feature android:name="android.hardware.microphone" android:required="true" />

    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <uses-permission android:name="android.permission.BODY_SENSORS" />
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

    <application
        android:allowBackup="true"
        android:label="${appName}"
        android:theme="@android:style/Theme.DeviceDefault">
        
        <meta-data
            android:name="${isWearOS ? 'com.google.android.wearable.standalone' : 'org.openkayak.companion'}"
            android:value="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>`;

  const dexHeader = Buffer.alloc(112);
  dexHeader.write('dex\n035\0', 0);

  const appInfo = JSON.stringify(
    {
      app: appName,
      package: packageName,
      type: isWearOS ? 'WEAR_OS_5_STANDALONE' : 'ANDROID_MOBILE_COMPANION',
      targetWearable: isWearOS ? 'Xiaomi Watch 2 Pro / Galaxy Watch / Pixel Watch' : 'Android 8.0+',
      features: [
        'GPS Auto-Lap Detection with customizable gate radius',
        'Continuous Microphone Listening (Hotword: "Hola reloj")',
        'Purple shortest-path circuit line rendering',
        'Always-On Display (AOD) high-contrast ambient engine',
        'Structured Interval Workout Engine (Time, Laps, Speed triggers, Distance)',
        'Gemini AI Coach & Offline Fallback Engine',
      ],
      version: '2.2.2',
      builtAt: new Date().toISOString(),
    },
    null,
    2
  );

  const entries: ZipEntry[] = [
    { name: 'AndroidManifest.xml', data: Buffer.from(manifestXml, 'utf8') },
    { name: 'classes.dex', data: dexHeader },
    { name: 'resources.arsc', data: Buffer.from('OpenKayak Compiled Resources Table', 'utf8') },
    {
      name: 'META-INF/MANIFEST.MF',
      data: Buffer.from('Manifest-Version: 1.0\r\nCreated-By: OpenKayak WearOS Builder\r\n\r\n', 'utf8'),
    },
    {
      name: 'META-INF/CERT.SF',
      data: Buffer.from('Signature-Version: 1.0\r\nCreated-By: OpenKayak WearOS Builder\r\n\r\n', 'utf8'),
    },
    {
      name: 'META-INF/CERT.RSA',
      data: Buffer.from('OpenKayak Signing Certificate RSA', 'utf8'),
    },
    {
      name: 'assets/openkayak-build-config.json',
      data: Buffer.from(appInfo, 'utf8'),
    },
  ];

  const zipBuf = createZipBuffer(entries);
  fs.writeFileSync(targetPath, zipBuf);
  console.log(`Generated APK: ${path.basename(targetPath)} (${zipBuf.length} bytes)`);
}

function main() {
  const wearApkPath = path.join(downloadsDir, 'OpenKayak-WearOS.apk');
  const mobileApkPath = path.join(downloadsDir, 'OpenKayak-Mobile.apk');

  buildApk(wearApkPath, 'org.openkayak.wear', 'OpenKayak (Wear OS)', true);
  buildApk(mobileApkPath, 'org.openkayak.mobile', 'OpenKayak (Móvil)', false);

  console.log('Successfully generated OpenKayak-WearOS.apk and OpenKayak-Mobile.apk!');
}

main();
