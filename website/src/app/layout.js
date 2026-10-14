import "./globals.css";

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL || "https://example.com";

export const metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: "ROM Packer — turn a Game Boy ROM into its own Android app",
    template: "%s · ROM Packer",
  },
  description:
    "ROM Packer builds a standalone Android app from a Game Boy, Game Boy Color or Game Boy Advance ROM file you already own. Everything happens on your phone.",
  openGraph: {
    title: "ROM Packer",
    description:
      "Turn a Game Boy, Color or Advance ROM you own into its own installable Android app.",
    url: SITE_URL,
    siteName: "ROM Packer",
    type: "website",
  },
  robots: { index: true, follow: true },
};

export const viewport = {
  themeColor: "#0f0d13",
};

function Header() {
  return (
    <header className="site-header">
      <div className="wrap">
        <a className="brand" href="/">
          <span className="brand-mark" aria-hidden="true" />
          ROM Packer
        </a>
        <nav className="site-nav">
          <a href="/#how">How it works</a>
          <a href="/privacy">Privacy</a>
          <a href="https://github.com/fixmylifedesigns/rom-packer">GitHub</a>
        </nav>
      </div>
    </header>
  );
}

function Footer() {
  return (
    <footer className="site-footer">
      <div className="wrap">
        <nav>
          <a href="/">Home</a>
          <a href="/privacy">Privacy</a>
          <a href="https://github.com/fixmylifedesigns/rom-packer">Source code</a>
          <a href="https://github.com/fixmylifedesigns/rom-packer/releases">Releases</a>
        </nav>
        <p>
          ROM Packer is an independent project. It is not affiliated with, endorsed by, or
          connected to Nintendo or any other rights holder. Game Boy, Game Boy Color and Game
          Boy Advance are trademarks of their respective owners.
        </p>
        <p>No games or ROM files are included or distributed.</p>
      </div>
    </footer>
  );
}

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>
        <Header />
        <main>{children}</main>
        <Footer />
      </body>
    </html>
  );
}
