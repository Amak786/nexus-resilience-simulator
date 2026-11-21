# Deployment

TRACE ships as one Docker image (Spring Boot API + built React site). The existing free Render web service is reused; its
infrastructure name and URL (`nexus-resilience`, https://nexus-resilience.onrender.com) are kept because renaming a Render service creates
a new service and URL. Everything users see is TRACE.

## Render (existing service)

1. Push to `main` on GitHub.
2. Render dashboard → service → **Environment**: add `TRACE_INGEST_TOKEN` and `TRACE_VIEW_TOKEN` (random 32-hex strings, different from
   each other). Optionally `ANTHROPIC_API_KEY` and `ABUSEIPDB_API_KEY`. Never commit them.
3. **Manual Deploy → Deploy latest commit** (the service is linked to a public repository URL, so auto-deploy is not available).
4. Check: `/api/health` returns `UP`; `/api/sources` shows Feodo and Spamhaus `OK` within a minute of start-up.
5. Run a sensor against the Render URL (see README). The free instance sleeps after 15 minutes without requests; while a sensor uploads
   every 15 s it stays awake. Observations are kept in memory, so a restart starts with an empty store (by design; see limitations).

## Local Docker

```bash
docker build -t trace .
docker run -p 8080:8080 -e TRACE_INGEST_TOKEN=... -e TRACE_VIEW_TOKEN=... trace
```

## Costs

Render free tier, abuse.ch, Spamhaus DROP and RIPEstat: free. AbuseIPDB free tier: 1,000 checks/day (TRACE caps at 500 by default).
Claude API (optional): billed per token to the key owner.
