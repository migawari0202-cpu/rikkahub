# Changelog

## 2026-10-28

- Added a built-in OpenAI-compatible provider for Liquid AI, using the `https://api.liquid.ai/v1` endpoint.
- This allows the app to connect to models exposed through the Liquid AI console with the standard `Authorization: Bearer <api_key>` format.

## 2026-10-26

- Adjusted the app icon brightness to improve visibility.
- Added `delegate_research` for parallel research by the configured fast model, with web search enabled for sub-agents.
- Fixed sub-agent function tools being omitted when the configured model was not marked tool-capable.

> Upstream changes are intentionally not listed here, in accordance with the README.
