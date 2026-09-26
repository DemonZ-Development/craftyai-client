# Changelog

All notable changes to the CraftyAI Minecraft client are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v1.4.0] - 2026-09-20

CraftyAI 1.4.0 is a major update focused on surroundings scan feedback, stability on newer Minecraft versions, expanded loader compatibility, and a significantly higher free tier token quota.

### Added
- Multi-version matrix loader support for Fabric (1.20.4–26.3), Forge (1.20.4–1.21.3), Modern Forge (26.x), NeoForge Legacy (1.20.4–1.20.6), and NeoForge Matrix (1.21.0–26.3).
- Dedicated client jars for Minecraft versions 1.20.4, 1.20.5, 1.20.6, 1.21 through 1.21.11, 26.1, 26.2, and 26.3.
- In-game settings screen tabs for General, Connection, and Providers (keybind **M** or mod settings).
- One-click "Auto-Mint Free Key" button inside the settings menu for instant client setup.
- Confirmation workflows for actions requiring player approval; active action runs can be cancelled at any time via `/craftyclient cancel`.
- Natural chat summary feedback for surroundings scans, reporting detected entities, mobs, blocks, and biomes.

### Changed
- Free tier token limit raised to 500,000 tokens per day.
- Server startup handshakes and heartbeats are now decoupled from daily chat token limits.
- Action execution now inspects actual command feedback and results before planning subsequent actions.
- Survival mode operator permission checks now allow operators to run authorized commands without creative mode.

### Fixed
- Fixed client crash when clicking "Save & close" on Minecraft 26.x caused by upstream changes to screen fields.
- Fixed settings persistence issues so configuration changes reliably save across game sessions.
- Fixed surroundings scans returning only placeholder messages.
- Fixed action loop aborting when repeated scan triggers occurred during harness execution.

## [v1.3.2] - 2026

CraftyAI's Minecraft client is now fully open source, licensed under Apache-2.0. The Spigot/Paper plugin and the Fabric, Forge, and NeoForge mods are all released under the CraftyAI name — built in public, the way it should be.

### Added
- Open-sourced the entire Minecraft client (plugin + mods) under the Apache-2.0 license
- Public build and release pipelines for Spigot/Paper, Fabric, and Forge/NeoForge
- Issue, pull request, and security templates to welcome community contributions

### Changed
- All components version-bumped to v1.3.2
- License headers and mod metadata updated to Apache-2.0

## [v1.3.1] - 2026

### Added
- Keybinding customization — open settings with **M** (default) or trigger a Vision Scan with **V** (default), both rebindable in the Controls menu.
- In-game "Auto-Mint Free Key" button inside the settings menu (`/crafty settings` or key **M**).
- Client commands for Forge: `/crafty`, `/crafty apikey`, `/crafty ask`, `/crafty scan`, `/crafty settings`, `/crafty status`.

### Fixed
- Screen crash on NeoForge 26.x when opening settings reflectively.
- Command and keybinding reliability — direct Brigadier API calls fix obfuscated `Commands.literal` and `KeyMapping.consumeClick` behavior across versions.
- Client-side crashes from server messages — now sent through a safe multi-version helper.

[Unreleased]: https://github.com/DemonZ-Development/craftyai-client/compare/v1.4.0...HEAD
[v1.4.0]: https://github.com/DemonZ-Development/craftyai-client/releases/tag/v1.4.0
[v1.3.2]: https://github.com/DemonZ-Development/craftyai-client/releases/tag/v1.3.2
[v1.3.1]: https://github.com/DemonZ-Development/craftyai-client/releases/tag/v1.3.1
