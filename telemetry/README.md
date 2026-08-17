# InnerDesk telemetry (Cloudflare Worker)

Anonymous **install heartbeats** + **crash/error reports** for InnerDesk.

Licensed with the rest of InnerDesk under **AGPL-3.0-or-later** — see [`LICENSE`](../LICENSE) and [`NOTICE`](../NOTICE).

- Client sends a random UUID (not an account or Google id).
- Users can turn this off in **About → Privacy**.
- No pairing PINs, no overlay dumps as a dedicated field — crash payloads are redacted.

## Endpoints

| Method | Path | Notes |
|--------|------|--------|
| `POST` | `/v1/ping` | `uuid`, `type` (`ping`\|`crash`\|`error`), `version`, `versionCode`, `androidSdk`, `locale`, optional `payload` |
| `GET` | `/v1/stats` | Aggregates. If `ADMIN_TOKEN` is set, requires `Authorization: Bearer …` |
| `GET` | `/health` | Liveness |

## Deploy

```bash
cd telemetry
npm install
npx wrangler d1 create innerdesk-telemetry
# paste database_id into wrangler.jsonc
npm run db:init
npx wrangler secret put ADMIN_TOKEN
npm run deploy
```
