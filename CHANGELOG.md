# Changelog

All notable changes to the CraftyAI Minecraft client are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Open-source release of the CraftyAI Minecraft client under the Apache-2.0 license.

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

[Unreleased]: https://github.com/DemonZ-Development/craftyai-client/compare/v1.3.2...HEAD
[v1.3.2]: https://github.com/DemonZ-Development/craftyai-client/releases/tag/v1.3.2
[v1.3.1]: https://github.com/DemonZ-Development/craftyai-client/releases/tag/v1.3.1
