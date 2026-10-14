// The app icon lives once in the repo, as the base64 PNG the Android build
// decodes for its launcher icons. The site decodes the same file at build time
// so there's no second copy to keep in sync (and no binary in git).
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, "../../builder/icon-source.png.b64");
const target = resolve(here, "../public/icon.png");

const encoded = await readFile(source, "utf8");
const bytes = Buffer.from(encoded.trim(), "base64");
if (bytes.length < 1000 || bytes.subarray(1, 4).toString() !== "PNG") {
  throw new Error(`${source} did not decode to a PNG`);
}
await mkdir(dirname(target), { recursive: true });
await writeFile(target, bytes);
console.log(`icon.png written (${bytes.length} bytes)`);
