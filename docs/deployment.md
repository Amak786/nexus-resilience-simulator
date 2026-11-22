# Deployment

TRACE runs as one Docker image (Spring Boot API + built React site) on a free Render web service named **trace**:
https://trace-qol8.onrender.com. (An older service, `nexus-resilience`, still exists from the previous project and can be deleted.)

## Render
1. Push to `main` on GitHub.
2. Render → service **trace** → Environment: `TRACE_INGEST_TOKEN` and `TRACE_VIEW_TOKEN` (Render's *Generate* button creates random values;
   each must be at least 16 characters). Optional: `ANTHROPIC_API_KEY`, `ABUSEIPDB_API_KEY`, `TRACE_DATA_DIR`.
3. **Manual Deploy → Deploy latest commit** (the service is linked to the public repository URL, so it does not auto-deploy).
4. Verify: `/api/health` (`startedAt` changes after a deploy), `/api/sources` (`viewerTokenState` and `ingestTokenState` must be `OK`,
   feeds `OK` within a minute).
5. Free instances sleep after ~15 minutes without requests; a sensor uploading every 15 s keeps it awake. The disk is ephemeral, so
   observations are lost on restart even with `TRACE_DATA_DIR` (that option is for laptops and VPS installs).

## Local Docker

```bash
docker build -t trace .
docker run -p 8080:8080 -e TRACE_INGEST_TOKEN=... -e TRACE_VIEW_TOKEN=... trace
```

## Costs

Render free tier, abuse.ch, Spamhaus DROP and RIPEstat: free. AbuseIPDB free tier: 1,000 checks/day (TRACE caps at 500 by default).
Claude API (optional): billed per token to the key owner.
