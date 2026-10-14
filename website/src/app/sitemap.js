// Required by output: "export" — these are generated at build time.
export const dynamic = "force-static";

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL || "https://example.com";

export default function sitemap() {
  const now = new Date();
  return ["", "/privacy"].map((path) => ({
    url: `${SITE_URL}${path}`,
    lastModified: now,
  }));
}
