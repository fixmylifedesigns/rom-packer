export const metadata = {
  title: "Privacy policy",
  description:
    "What ROM Packer does and does not do with your files, your ROMs and your data. Written from the app's actual behaviour.",
};

// Owner must fill these before publishing. They are the only unverifiable
// details on this page; everything else is taken from the app's source.
const PUBLISHER = "[publisher legal name]";
const CONTACT = "[privacy contact email]";
const EFFECTIVE = "[effective date]";

function Fill({ children }) {
  return <span className="placeholder">{children}</span>;
}

export default function Privacy() {
  return (
    <div className="wrap doc">
      <h1>Privacy policy</h1>
      <p className="updated">
        Effective <Fill>{EFFECTIVE}</Fill> · applies to the ROM Packer Android app and the apps it
        generates.
      </p>

      <div className="callout">
        <p>
          <strong>The short version.</strong> ROM Packer has no accounts, no analytics, no
          advertising and no crash reporting. Your ROM files, the apps built from them and your
          save data stay on your device. The app talks to the network for exactly three things:
          checking for its own updates, downloading cover art, and downloading a game-identification
          database.
        </p>
      </div>

      <h2>Who is responsible</h2>
      <p>
        This app is published by <Fill>{PUBLISHER}</Fill>. Questions about privacy can be sent to{" "}
        <Fill>{CONTACT}</Fill>.
      </p>

      <h2>What stays on your device</h2>
      <p>
        All of the following is read, written and stored locally. None of it is transmitted to the
        developer or to any third party:
      </p>
      <ul>
        <li>
          <strong>ROM files you select.</strong> The file you choose is read through Android&apos;s
          document picker, copied into the app it generates, and otherwise left alone. It is never
          uploaded.
        </li>
        <li>
          <strong>Generated apps.</strong> Each APK is built and signed on your phone and written to
          the app&apos;s private cache until you install or save it.
        </li>
        <li>
          <strong>The signing key.</strong> A key is generated on your device the first time you
          build a game and kept in the app&apos;s private storage, so later rebuilds install as
          updates. It never leaves the device.
        </li>
        <li>
          <strong>Icons and artwork</strong> you pick or that are downloaded, including the cropped
          result stored inside the generated app.
        </li>
        <li>
          <strong>Save data.</strong> Battery saves, save states and their thumbnails live in each
          generated game&apos;s private storage.
        </li>
        <li>
          <strong>Your settings</strong>, such as the rotation preference and when updates were last
          checked.
        </li>
      </ul>
      <p>
        Uninstalling an app removes its private storage, including that game&apos;s saves. Save files
        you exported yourself are yours and are unaffected.
      </p>

      <h2>Network requests the app makes</h2>
      <p>These are the only outbound requests in the code, and what triggers each one:</p>
      <table className="data">
        <thead>
          <tr>
            <th>Request</th>
            <th>When</th>
            <th>What is sent</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>GitHub releases API</td>
            <td>
              On launch, at most once every 12 hours, and when you tap Update. Generated games make
              the same request only when you choose Check for updates.
            </td>
            <td>A plain request for the latest release. No identifiers are attached.</td>
          </tr>
          <tr>
            <td>GitHub release download</td>
            <td>Only if you accept an update.</td>
            <td>A request for the published APK file.</td>
          </tr>
          <tr>
            <td>Game database files, hosted on GitHub</td>
            <td>The first time cover art is looked up for a system; cached for 14 days.</td>
            <td>
              A request for a public database file. The ROM is matched locally after download — your
              ROM is not sent anywhere.
            </td>
          </tr>
          <tr>
            <td>Cover art images, hosted on GitHub</td>
            <td>When artwork is found for your game.</td>
            <td>A request for a public image by its game title.</td>
          </tr>
        </tbody>
      </table>
      <p>
        Because these requests go to GitHub, GitHub receives what any web server receives: your IP
        address, the time, and a user agent. That data is handled under GitHub&apos;s own privacy
        statement, not by this project. ROM Packer sends no account, device or advertising
        identifier with any request, and the developer operates no server and receives no logs.
      </p>

      <h2>What the app does not do</h2>
      <ul>
        <li>No analytics or usage tracking SDK.</li>
        <li>No advertising, and no advertising identifier is read.</li>
        <li>No crash or performance reporting service.</li>
        <li>No accounts, sign-in, email collection or newsletters.</li>
        <li>No location, contacts, camera, microphone or health data access.</li>
        <li>No selling or sharing of personal information, because none is collected.</li>
      </ul>

      <h2>Permissions and why they exist</h2>
      <ul>
        <li>
          <strong>Internet</strong> — the four requests listed above.
        </li>
        <li>
          <strong>Install packages</strong> — so the app you just built can be handed to
          Android&apos;s installer. Android still asks you to confirm every installation.
        </li>
        <li>
          <strong>Query installed packages</strong> — to list the games ROM Packer previously built,
          so they can be rebuilt on a newer player. The list is read on the device and never leaves
          it.
        </li>
        <li>
          <strong>Vibrate</strong> (generated games) — haptic feedback on the on-screen controls.
        </li>
      </ul>
      <p>
        File access uses Android&apos;s document picker, which grants access only to the specific
        file you choose. The app requests no broad storage permission.
      </p>

      <h2>Children</h2>
      <p>
        ROM Packer is a utility for people who already have their own game files. It is not directed
        at children, and it collects no personal information from anyone.
      </p>

      <h2>Distribution channels</h2>
      <p>
        ROM Packer is currently distributed as an APK through GitHub releases, and that build checks
        GitHub for its own updates as described above. If a Google Play build is released later,
        update checking there would be handled by Google Play and this policy will be revised to
        describe any difference before that build ships.
      </p>

      <h2>Your rights</h2>
      <p>
        There is no account to access, correct or delete, because no personal data is collected or
        stored off your device. To remove everything the app holds, uninstall ROM Packer and any
        games it generated. For questions, or to raise a concern under laws such as the GDPR or
        CCPA, contact <Fill>{CONTACT}</Fill>.
      </p>

      <h2>Changes to this policy</h2>
      <p>
        If the app&apos;s behaviour changes — a new network request, a new permission — this page is
        updated and the effective date above changes with it. The history of this page is public in
        the project repository.
      </p>
    </div>
  );
}
