const RELEASES = "https://github.com/fixmylifedesigns/rom-packer/releases/latest";
const REPO = "https://github.com/fixmylifedesigns/rom-packer";

const FEATURES = [
  {
    title: "Game Boy, Color and Advance",
    body: "One packer for all three. The system is detected from the cartridge header, not the file name.",
  },
  {
    title: "Cover art, found for you",
    body: "The ROM's checksum is matched against open No-Intro data, and the box art is fetched automatically. You can also search by cartridge code or title, or use your own image.",
  },
  {
    title: "Crop and adjust the icon",
    body: "Pan, pinch and rotate inside a square frame, with brightness, contrast and saturation. What you see is what lands on your home screen.",
  },
  {
    title: "Saves that behave",
    body: "Battery saves and eight numbered save-state slots with thumbnails. Import a .sav from another emulator, or export yours out.",
  },
  {
    title: "Controller support",
    body: "On-screen controls with a proper D-pad, or any Bluetooth gamepad Android recognises.",
  },
  {
    title: "Everything on the phone",
    body: "Your ROM never leaves the device. The APK is built and signed locally, with no account and no upload.",
  },
];

const FAQ = [
  {
    q: "Does ROM Packer include any games?",
    a: "No. It contains no ROMs and distributes none. You supply a game file you already have the rights to use.",
  },
  {
    q: "Where does my ROM go?",
    a: "Nowhere. The file is read on your device, packed into the generated app, and stays on your phone. It is never uploaded.",
  },
  {
    q: "Why does Android warn me when installing?",
    a: "Apps installed outside the Play Store are unsigned by Google, so Android asks you to confirm. Grant ROM Packer permission to install apps when prompted, and leave Play Protect on.",
  },
  {
    q: "Will my saves survive an update?",
    a: "Yes. Rebuilt games keep the same package id and signing key, so Android treats the new build as an update and the save files in private storage are untouched.",
  },
  {
    q: "Can I move a save from another emulator?",
    a: "Battery saves are raw SRAM dumps, the same format mGBA, VBA-M and RetroArch write, so a .sav imports directly. Save states are emulator-specific and only load back into ROM Packer's player.",
  },
  {
    q: "Is it on Google Play?",
    a: "Not yet. Today it is distributed as an APK through GitHub releases.",
  },
];

export default function Home() {
  return (
    <>
      <div className="wrap hero">
        <span className="eyebrow">Android · free · open source</span>
        <h1>Turn a Game Boy ROM into its own app.</h1>
        <p className="lead">
          ROM Packer takes a game file you already own and builds a standalone Android app from
          it — your cover art on the home screen, the emulator built in. No account, no upload,
          nothing leaves your phone.
        </p>
        <div className="actions">
          <a className="btn btn-primary" href={RELEASES}>
            Download the APK
          </a>
          <a className="btn btn-ghost" href={REPO}>
            View source
          </a>
          <span className="btn btn-disabled" aria-disabled="true">
            Google Play — coming later
          </span>
        </div>
      </div>

      <section id="how">
        <div className="wrap">
          <h2>How it works</h2>
          <p className="section-note">Four steps, all on the device.</p>
          <ol className="steps">
            <li>
              <strong>Pick your ROM</strong>
              Choose a .gb, .gbc or .gba file, or open one straight from your file manager.
            </li>
            <li>
              <strong>Get the artwork</strong>
              Cover art is matched from the ROM itself. Swap in your own image and crop it if you
              prefer.
            </li>
            <li>
              <strong>Name it</strong>
              Set the app name and its package id, which keeps each game separate on your phone.
            </li>
            <li>
              <strong>Build and install</strong>
              ROM Packer builds and signs the APK on your phone, then hands it to Android to
              install. You can also save it to share or keep.
            </li>
          </ol>
        </div>
      </section>

      <section>
        <div className="wrap">
          <h2>What you get</h2>
          <p className="section-note">
            Each generated app is a complete game: emulator, controls and saves, under its own icon.
          </p>
          <div className="cards">
            {FEATURES.map((f) => (
              <article className="card" key={f.title}>
                <h3>{f.title}</h3>
                <p>{f.body}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section>
        <div className="wrap">
          <h2>Bring your own games</h2>
          <div className="callout">
            <p>
              ROM Packer ships with no game content whatsoever. You are responsible for having the
              right to use any ROM you supply, and for complying with the copyright law that
              applies where you live. The project is not affiliated with Nintendo or any other
              rights holder.
            </p>
          </div>
        </div>
      </section>

      <section>
        <div className="wrap">
          <h2>Questions</h2>
          <p className="section-note">The things worth knowing before you install.</p>
          {FAQ.map((item) => (
            <details key={item.q}>
              <summary>{item.q}</summary>
              <p>{item.a}</p>
            </details>
          ))}
        </div>
      </section>

      <section>
        <div className="wrap">
          <h2>Get it</h2>
          <p className="section-note">
            Built by GitHub Actions from the source in this repository. Download the APK from the
            latest release.
          </p>
          <div className="actions">
            <a className="btn btn-primary" href={RELEASES}>
              Latest release
            </a>
            <a className="btn btn-ghost" href={`${REPO}/issues`}>
              Report a problem
            </a>
          </div>
        </div>
      </section>
    </>
  );
}
