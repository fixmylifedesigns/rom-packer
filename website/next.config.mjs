/** @type {import('next').NextConfig} */
const nextConfig = {
  // Static export: the site is all content, so it can sit on any static host
  // (Netlify, Cloudflare Pages, GitHub Pages) with no server runtime.
  output: "export",
  images: { unoptimized: true },
};

export default nextConfig;
