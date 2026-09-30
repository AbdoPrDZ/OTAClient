
/// <reference types="node" />
import "dotenv/config";
import { execSync } from "child_process";
import * as tar from "tar";
import fs from "fs-extra";
import crypto from "crypto";
import { join } from "path";

export async function getFileInfo(filePath: string): Promise<{ checksum: string, size: number } | null> {
  if (!fs.existsSync(filePath)) {
    return null;
  }

  const fileBuffer = fs.readFileSync(filePath);
  const size = fs.statSync(filePath).size;
  const checksum = crypto.createHash('md5').update(fileBuffer).digest('hex');

  return { checksum, size };
}

/**
 * Release-only bundle script
 * - builds only the JS bundle (no res assets)
 * - writes a minimal manifest.json next to the bundle in a temp folder
 * - archives the bundle+manifest into release/{name}-{version}.tar.gz
 * - leaves the `release` folder containing only .tar.gz files
 */

async function main() {
  const ROOT = process.cwd();

  const pkgPath = join(ROOT, "package.json");
  if (!(await fs.pathExists(pkgPath))) {
    console.error("package.json not found in project root");
    process.exit(1);
  }

  const pkg = await fs.readJson(pkgPath);
  const appName = pkg.name;
  const version = pkg.version;
  const runtimeVersion = pkg.runtimeVersion;
  const buildName = `${appName}-${runtimeVersion}-${version}`;

  if (!appName) {
    console.error("App name not specified in package.json");
    process.exit(1);
  }

  if (!version) {
    console.error("Version not specified in package.json");
    process.exit(1);
  }

  if (!runtimeVersion) {
    console.error("Runtime version not specified in package.json");
    process.exit(1);
  }

  const bundleFileName = (process.env.BUILD_BUNDLE_NAME || "@version.android.bundle").trim()
												 .replaceAll("@version", `${runtimeVersion}-${version}`)
                         .replaceAll("@date", new Date().toISOString());

  const tmpDir = process.env.BUILD_TMP_DIR || join(ROOT, "tmp");
  const tmpBuildDir = join(tmpDir, buildName);

  const releaseDir = process.env.RELEASE_DIR || join(ROOT, "release");

  // ensure clean tmp dir
  if (await fs.pathExists(tmpBuildDir)) {
    await fs.remove(tmpBuildDir);
  }
  await fs.ensureDir(tmpBuildDir);

  const bundleOut = join(tmpBuildDir, bundleFileName);

  console.log(`Building bundle v${version} -> ${bundleOut}`);

  // Run react-native bundle but only write the bundle file (no assets-dest)
  const cmd = [
    "npx react-native bundle",
    "--platform android",
    "--dev false",
    "--entry-file index.js",
    `--bundle-output "${bundleOut}"`,
  ].join(" ");

  try {
    execSync(cmd, { stdio: "inherit" });
  } catch (e: any) {
    console.error("react-native bundle failed:", e?.message || e);
    process.exit(2);
  }

  const { checksum, size } = (await getFileInfo(bundleOut))!

  const manifest = {
    version,
    runtimeVersion,
    bundle: bundleFileName,
    checksum,
    size,
    createdAt: new Date().toISOString(),
  };

  const manifestPath = join(tmpBuildDir, "manifest.json");
  await fs.writeFile(manifestPath, JSON.stringify(manifest, null, 2));

  // ensure release dir
  await fs.ensureDir(releaseDir);

  const archiveName = `${buildName}.tar.gz`;
  const archivePath = join(releaseDir, archiveName);

  console.log(`Creating archive ${archivePath} (contains bundle + manifest)`);

  try {
    // create tar.gz with entries at the root of the tar (bundle filename + manifest.json)
    await tar.create(
      {
        gzip: true,
        file: archivePath,
        cwd: tmpBuildDir,
      },
      [bundleFileName, "manifest.json"]
    );
  } catch (e: any) {
    console.error("Failed to create archive:", e?.message || e);
    process.exit(3);
  }

  // clean tmp
  try {
    await fs.remove(tmpBuildDir);
  } catch {
    // non-fatal
  }

  console.log("Release bundle created:", archivePath);
}

main();
