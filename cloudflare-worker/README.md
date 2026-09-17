# AZ Learning Gateway (Cloudflare Worker)

This backend receives only anonymous resolved numeric outcomes from AZ. It does
not accept screenshots, broker credentials, Android identifiers or GitHub
credentials. It validates and deduplicates each row, aggregates outcomes in D1,
and publishes a conservative community model to GitHub once daily.

## Deployment (requires a connected Cloudflare account)

1. Create D1: `npx wrangler d1 create az-learning`.
2. Replace `REPLACE_AFTER_D1_CREATE` in `wrangler.toml` with the database ID.
3. Apply schema: `npx wrangler d1 execute az-learning --remote --file=schema.sql`.
4. Create a fine-grained GitHub token restricted to **Contents: read/write** on
   `noorloja2-ai/AZ-CandleScanner` only.
5. Store it as a Worker secret: `npx wrangler secret put AZ_GITHUB_TOKEN`.
6. Deploy: `npx wrangler deploy`.
7. Test `https://<worker>.workers.dev/health`.
8. Put the deployed endpoint plus `/v1/learning/batch` into
   `community-learning.json` as `submit_url`.

Never commit the GitHub token. Android receives only the public gateway URL.
