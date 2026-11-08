# Local Docker Backend on macOS

This runs the existing Node.js 22 Gemini conversation API in Docker Compose on a Mac. It reuses `backend/Dockerfile`, publishes port `3000` on the Mac's selected Wi-Fi IPv4 address, and keeps the same image portable to Linux VPS hosts. It does not use Caddy or HTTPS. If `MAC_LOCAL_IP` is omitted, Compose binds the port to loopback only.

The local `.env` file is read by Docker Compose and passed to the container as environment variables. It is excluded from the Docker build context and image, and Git ignores it. Never put the Gemini key in source code, an Android build property, or the APK.

## One-time setup

1. Install Docker Desktop for Mac and start it.
2. If `backend/.env` does not exist, create it from the example:

   ```sh
   cp backend/.env.example backend/.env
   ```

3. Add the Gemini key to `backend/.env` and keep `AI_PROVIDER=gemini`, `AI_MODEL=gemini-2.5-flash`, and `PORT=3000`. Keep this local file private and untracked.

The Compose service has a health check against `/healthz`, restarts unless explicitly stopped, and keeps rotated container logs. If the API key is empty or invalid, `/healthz` reports that the service is not configured and Compose marks it unhealthy.

## Start and operate

Run this from the repository root to start on the Mac's Wi-Fi interface. It discovers the current address and starts or rebuilds the service in one command:

```sh
MAC_LOCAL_IP="$(ipconfig getifaddr en0)" docker compose up -d --build
```

Check health from the Mac:

```sh
curl --fail "http://$(ipconfig getifaddr en0):3000/healthz"
docker compose ps
```

Follow logs:

```sh
docker compose logs -f companion-api
```

Restart the running service:

```sh
docker compose restart companion-api
```

Stop the service:

```sh
docker compose down
```

Changing application code requires `MAC_LOCAL_IP="$(ipconfig getifaddr en0)" docker compose up -d --build` so Docker rebuilds the image. Changing only `backend/.env` requires recreating the container:

```sh
MAC_LOCAL_IP="$(ipconfig getifaddr en0)" docker compose up -d --force-recreate companion-api
```

## Connect the Android phone over local Wi-Fi

Keep the Mac powered on, Docker Desktop running, and the Mac and phone connected to the same private Wi-Fi network. Find the Mac's Wi-Fi IPv4 address with:

```sh
ipconfig getifaddr en0
```

If this prints no address, check the Wi-Fi connection details in macOS System Settings; the Wi-Fi interface name may differ. The Compose startup command binds port `3000` only to this Wi-Fi address. Use the same address in the Android build, replacing `MAC_LOCAL_IP` with the address you found:

```sh
./gradlew assembleDebug -PcompanionBackendUrl=http://MAC_LOCAL_IP:3000
```

For example, the address has the form `http://192.168.x.x:3000`; do not place a real machine address in application source code. The equivalent environment-variable form is:

```sh
COMPANION_BACKEND_URL=http://MAC_LOCAL_IP:3000 ./gradlew assembleDebug
```

The Gradle property takes precedence when both settings are provided. If the backend URL is empty or omitted, Android continues to use its existing mock conversation fallback. The provider key is never passed to Gradle or Android.

This HTTP setup is only for a trusted private LAN. It does not encrypt conversation text between the phone and Mac. Use a private Wi-Fi IPv4 address; do not use public Wi-Fi, configure router port forwarding, or expose port `3000` to the public internet. Docker binds the port to all interfaces if no host address is supplied, so this Compose file uses the selected Wi-Fi address and falls back to loopback if it is missing. If the Mac firewall blocks the phone, allow Docker Desktop or the local network connection in macOS Firewall settings. Router Wi-Fi client isolation can also prevent devices from reaching one another.

The address is available only while the Mac is powered on, connected to Wi-Fi, and running the Compose service. This setup does not provide access from outside the local network.

If the Mac receives a different Wi-Fi address, recreate the container using the updated address:

```sh
MAC_LOCAL_IP="$(ipconfig getifaddr en0)" docker compose up -d --force-recreate companion-api
```

## Portability

The Compose file builds the same `backend/Dockerfile` used by the Oracle Cloud deployment. The Node server listens on `0.0.0.0` inside the container; Compose binds the host port to the selected host address. On another Docker-compatible host, provide `MAC_LOCAL_IP` if remote LAN access is needed, provide a host-only environment file, and run `docker compose up -d --build`. Keep the Oracle-specific files under `deploy/oracle-cloud/` and `ORACLE_CLOUD_DEPLOYMENT.md` for a future VPS deployment; this local Compose setup does not alter them.
