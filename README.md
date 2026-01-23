# Monarch Hytale Discord Companion

`Monarch Hytale Discord Companion` by **msdigital** mirrors your Hytale server status into Discord, lets players validate whitelist codes directly from Discord, and posts graceful shutdown notices plus scripted announcements back into the game.

## Quick start

1. Install **Hytale 1.0+** on the same machine that hosts this plugin. The official installer keeps the server runtime under `%HYLTALE_HOME%/install/<patchline>`.
2. Ensure **Java 25 (Temurin 25)** is available through `JAVA_HOME` or your shell `PATH`; Gradle uses it to compile and package the plugin.
3. Build the plugin and release bundle with the Gradle wrapper:

   ```shell
   ./gradlew clean release
   ```

   The fat JAR is emitted as `build/libs/monarch-hytale-discord-companion-<version>-fat.jar`, and the release ZIP lands at `build/release/monarch-hytale-discord-companion-<version>.zip`.
4. Copy one of those artifacts into your Hytale `mods/` directory and start the server once so the plugin can create `mods/DiscordCompanion/discord.yml`.
5. Populate `mods/DiscordCompanion/discord.yml` with your token, guild/conversation IDs, and other preferences (use the shipped `discord.yml.example` for reference if you like), then restart the server so the plugin reloads the configuration.

### CurseForge installation

1. Download the latest ZIP or fat JAR from this repository's [GitHub releases](https://github.com/msdigital/monarch-hytale-discord-companion/releases) or the `build/release/` artifact produced by the release workflow.
2. Put `monarch-hytale-discord-companion-<version>-fat.jar` in your Hytale `mods/` folder.
3. Copy `discord.yml.example` into `mods/DiscordCompanion/` before the first run so you can edit it, or edit the generated `mods/DiscordCompanion/discord.yml` afterwards.
4. Restart the server to make sure the new configuration is loaded.

> **Release workflow:** `.github/workflows/release.yml` runs on every published release, executes `./gradlew clean release` with Java 25, and uploads the release ZIP that ships the fat JAR along with the bundled README and LICENSE.

## Discord configuration

Configure `mods/DiscordCompanion/discord.yml` (or copy `discord.yml.example`) with the following keys:

| Property | Description |
| --- | --- |
| `token` | Bot token from https://discord.com/developers/applications (required). |
| `guild-id` | Optional; limits slash commands to that guild so Discord registers them instantly. |
| `status-channel-id` | Channel where the `/status` embed and presence updates post. |
| `shutdown-channel-id` | Channel for the server shutdown notice. |
| `shutdown-message` | Text broadcast before the bot and plugin stop. |
| `set-presence` | `true` (default) to keep the bot presence showing the player counts. |
| `presence-format` | Format string using `{online}` and `{max}` (default `Players {online}/{max}`). |
| `max-players` | Optional override when Hytale does not expose a hard cap. |
| `language` | Locale code that selects the JSON file under `locales/` (en by default). |
| `announcement-role-id` | Role that can execute `/announcement`. |

Restart the server after saving the configuration so the plugin reloads the values.

## Where to find the Discord values

1. **Bot token:** Create an application at https://discord.com/developers/applications, add a bot user, enable the required intents, and copy the token from the Bot page.
2. **Guild ID:** Enable Developer Mode in Discord (Settings → Advanced) and right-click the server icon to _Copy ID_.
3. **Channel IDs:** With Developer Mode enabled, open each channel, right-click, and _Copy ID_.
4. **Announcement role ID:** Right-click the role in the server's role list and _Copy ID_. Only members with that role can see or execute `/announcement`.

After recording the values, restart the server so the plugin picks them up.

## Discord-driven features

- **Presence & status embed:** The bot advertises the current player count and refreshes the embed using localized strings inside `src/main/resources/locales/` (the English template is automatically created on the first run).
- **Whitelist flow:** Non-whitelisted players are disconnected while joining, asked to run `/whitelist <code>` in Discord, and the embedded SQLite service keeps track of issued codes and their validation state.
- **Announcements:** `/announcement` updates the `SERVER INFO` world notification, repeats the same text via `echo` in global chat, and only the configured `announcement-role-id` can issue it.
- **Shutdown notification:** When the plugin or server begins shutting down, the configured shutdown message is posted to the target channel so players know when to return.

## Development & contribution

1. Clone the repository and run `./gradlew clean build` to verify that the fat JAR and release ZIP compile without errors.
2. Keep localization JSON files under `src/main/resources/locales/` so translators can add or update the language packs (an `en.json` stub is generated automatically).
3. If you change any Discord commands, update `DiscordEventListener` and refresh the bot with `/discord reload` (if you expose such a command).

Contributions are welcome—please open issues on github.com/msdigital/monarch-hytale-discord-companion before submitting pull requests.
