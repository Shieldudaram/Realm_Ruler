# 🛠️ Hytale Plugin Template

Welcome to the **Hytale Plugin Template**! This project is a pre-configured foundation for building **Java Plugins**. It streamlines the development process by handling classpath setup, server execution, and asset bundling.

> **⚠️ Early Access Warning**
> Hytale is currently in Early Access. Features, APIs, and this template are subject to frequent changes. Please ensure you are using the latest version of the template for the best experience.

---

## 📋 Prerequisites

Before you begin, ensure your environment is ready:

* **Hytale Launcher**: Installed and updated.
* **Java 25 SDK**: Required for modern Hytale development.
* **IntelliJ IDEA**: (Community or Ultimate) Recommended for full feature support.

---

## 🚀 Quick Start Installation

### 1. Initial Setup (Before Importing)

To avoid IDE caching issues, configure these files **before** you open the project in IntelliJ:

* **`settings.gradle`**: Set your unique project name.
```gradle
rootProject.name = 'MyAwesomePlugin'

```


* **`gradle.properties`**: Set your `maven_group` (e.g., `com.yourname`) and starting version.
* **`src/main/resources/manifest.json`**: Update your plugin metadata.
* **CRITICAL:** Ensure the `"Main"` property points exactly to your entry-point class.



### 2. Importing the Project

1. Open IntelliJ IDEA and select **Open**.
2. Navigate to the template folder and click **OK**.
3. Wait for the Gradle sync to finish. This will automatically download dependencies, create a `./run` folder, and generate the **HytaleServer** run configuration.

### 3. Authenticating your Test Server

You **must** authenticate your local server to connect to it:

1. Launch the **HytaleServer** configuration in IDEA.
2. In the terminal, run: `auth login device`.
3. Follow the printed URL to log in via your Hytale account.
4. Once verified, run: `auth persistence Encrypted`.

---

## 🎮 Developing & Testing

### Running the Server

If you do not see the **HytaleServer** run configuration in the top-right dropdown, click "Edit Configurations..." to unhide it. Press the **Green Play Button** to start, or the **Bug Icon** to start in Debug Mode to enable breakpoints.

### Verifying the Setup

1. Launch your standard Hytale Client.
2. Connect to `Local Server` (127.0.0.1).
3. Type `/test` in-game. If it returns your plugin version, everything is working!

### Bundling Assets

You can include models and textures by placing them in `src/main/resources/Common/` or `src/main/resources/Server/`. These are editable in real-time using the in-game **Asset Editor**.

---

## 📦 Building your Plugin

To create a shareable `.jar` file for distribution:

1. Open the **Gradle Tab** on the right side of IDEA.
2. Navigate to `Tasks` -> `build` -> `build`.
3. Your compiled plugin will be in: `build/libs/your-plugin-name-1.0.0.jar`.

To install it manually, drop the JAR into `%appdata%/Hytale/UserData/Mods/`.

---

## 🔖 Versioning & Releases

### Version Policy

This repo follows **Semantic Versioning**: `MAJOR.MINOR.PATCH`.

* `PATCH`: bug fixes or safe internal changes (`1.2.3` -> `1.2.4`)
* `MINOR`: new backward-compatible features (`1.2.3` -> `1.3.0`)
* `MAJOR`: breaking behavior or compatibility changes (`1.x` -> `2.0.0`)

The version source of truth is `version=` in `gradle.properties`.
`manifest.json` is kept in sync by the build and release automation.

### PR Title Rules (Required)

Release automation infers version bumps from PR titles. Use conventional PR titles such as:

* `fix: correct CTF flag reset`
* `feat: add match warmup countdown`
* `feat!: remove legacy spawn command`

If a change is breaking, use `!` after the type (or include `BREAKING CHANGE` in the PR description).

### Stable Release Flow (`main`)

1. Merge conventional-title PRs into `main`.
2. Release Please opens or updates a release PR with version/changelog changes.
3. Merge the release PR.
4. GitHub Actions creates a stable tag (`vX.Y.Z`), creates the GitHub Release, builds `realm_ruler-X.Y.Z.jar` on the self-hosted runner, and uploads that jar asset.

### Dev Prerelease Flow (`dev`)

1. Open **Actions** -> **Dev Prerelease**.
2. Run it from the `dev` branch.
3. Optionally pass `base_version` (otherwise it uses `gradle.properties`).
4. The workflow publishes `vX.Y.Z-dev.<run_number>` with a matching prerelease jar asset.

### Rollback Playbook

If a bad release is published:

1. Remove the bad GitHub Release and tag.
2. Merge a fix.
3. Publish a follow-up patch release (for example, `v1.2.4` -> `v1.2.5`) instead of reusing a tag.

---

## 📚 Advanced Documentation

For detailed guides on commands, event listeners, and professional patterns, visit our full documentation:
👉 **[Hytale Modding Documentation](https://britakee-studios.gitbook.io/hytale-modding-documentation)**

---

## 🆘 Troubleshooting

* **Sync Fails**: Check that your Project SDK is set to **Java 25** via `File > Project Structure`.
* **Cannot Connect**: Ensure you ran the `auth` commands in the server console.
* **Plugin Not Loading**: Double-check your `manifest.json` for typos in the `"Main"` class path.

---

**Need Help?** Visit our full guide here: **[Hytale Modding Documentation](https://britakee-studios.gitbook.io/hytale-modding-documentation)**
