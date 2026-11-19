# Deployment guide

NEXUS ships as **one Docker image** that contains the Spring Boot API and the built React website.
One free Render web service therefore gives you a single public HTTPS URL for everything.

## Option A (recommended): Render, single service

1. Push this repository to GitHub (public or private).
2. Sign in to <https://dashboard.render.com> and choose **New → Blueprint**.
3. Connect GitHub if asked and pick the repository. Render reads `render.yaml`:
   * service `nexus-resilience`, runtime Docker, plan **free**, health check `/api/health`.
4. Click **Apply**. The first build takes roughly 5-10 minutes (npm install, Maven download, compile).
5. Open the URL Render shows (for example `https://nexus-resilience.onrender.com`). Check:
   * `/api/health` returns `{"status":"UP", ...}`
   * `/` shows the NEXUS website
   * `/swagger-ui.html` shows the API documentation

**Free-tier notes.** The service sleeps after about 15 minutes without traffic; the next request takes about 30-60 seconds
while it wakes. Open the site a minute before a demo. Free instances have little CPU, so benchmark times will be higher than
on a laptop. Operation counts stay the same.

Alternative without a Blueprint: **New → Web Service**, choose the repository, **Language: Docker**, leave the Dockerfile path
as `./Dockerfile`, set the health check path to `/api/health`, and choose the free instance type.

## Option B: website on Vercel, API on Render

1. Deploy the API on Render as above.
2. On <https://vercel.com>: **Add New → Project**, import the repository, set **Root Directory** to `frontend`.
   Vercel detects Vite; `frontend/vercel.json` sets the build command and output directory.
3. Add the environment variable `VITE_API_BASE_URL = https://<your-render-service>.onrender.com` (no trailing slash) and deploy.
4. On Render, set `CORS_ALLOWED_ORIGINS = https://<your-vercel-project>.vercel.app` and redeploy the API.

## Local container

```bash
docker build -t nexus .
docker run -p 8080:8080 nexus
```

## Persistence

NEXUS has no database. The bundled synthetic dataset is inside the image, and each visitor's edits stay in their own browser
(local storage). This is deliberate: free hosting tiers have ephemeral disks, and a stateless API cannot lose or mix up
anyone's data.

## Security checklist

* No secrets are required or stored. `.env` files are git-ignored; only `.env.example` files are committed.
* CORS allows only the origins listed in `CORS_ALLOWED_ORIGINS` (same-origin requests need none).
* Request bodies over `MAX_REQUEST_BYTES` (2 MiB) are rejected with HTTP 413; graph sizes are capped in every service.
* Errors return `{error, message, issues}`; stack traces are never sent to clients.
* The container runs as a non-root user.
