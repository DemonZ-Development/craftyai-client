# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in CraftyAI, **please do not open a public issue**.

Instead, report it privately to the DemonZ Development team so we can address it before it is disclosed. Security issues in this repository concern the client-side plugin/mods (e.g., remote code execution, data leakage, auth bypass in the client configuration handling).

How to report:
- Open a private security advisory on GitHub: **Security → Report a vulnerability** in this repository
- Or contact the team via the official CraftyAI website: https://craftyai.pages.dev

Please include:
- A description of the vulnerability and its impact
- The affected component(s) and version(s)
- Steps to reproduce, or a minimal proof of concept
- Any relevant logs (with API keys and personal data redacted)

## Response Timeline

- **Acknowledgment**: within 48 hours
- **Assessment**: within 5 business days
- **Fix and disclosure**: as soon as a fix is available; coordinated disclosure otherwise

## Scope

This repository contains the open-source Minecraft client (Spigot/Paper plugin and Fabric/Forge mods). The backend service that powers CraftyAI is not part of this repository and is handled internally.

## Best Practices

- Never commit API keys, tokens, or connection keys into this repository.
- If you believe a key has been leaked, revoke it and mint a new one from the in-game settings.
