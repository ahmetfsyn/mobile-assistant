# Local Docker Backend on macOS

This runs the existing Node.js 22 Gemini conversation API in Docker Compose on a Mac. It reuses `backend/Dockerfile`, publishes port `3000` on the Mac's selected Wi-Fi IPv4 address, and keeps the same image portable to Linux VPS hosts. It does not use Caddy or HTTPS. If `MAC_LOCAL_IP` is omitted, Compose binds the port to loopback only.

The local `.env` file is read by Docker Compose and passed to the container as environment variables. It is excluded from the Docker build context and image, and Git ignores it. Never put the Gemini key in source code, an Android build property, or the APK.

## One-time setup

1. Install Docker Desktop for Mac and start it.
2. If `backend/.env` does not exist, create it from the example:

   ```sh
   cp backend/.env.example backend/.env
   ```

3. Add the Gemini key to `backend/.env` and keep `AI_PROVIDER=gemini`. Keep this local file private and untracked. Compose sets `PORT=3000` and selects `gemini-3.5-flash-lite` for the local service. Its `AI_MODEL` setting takes precedence over an `AI_MODEL` line in the local `.env` file. This stable model is listed by Google for the Gemini Developer API free tier and responded quickly in the device integration diagnosis.

The Compose service has a health check against `/healthz`, restarts unless explicitly stopped, and keeps rotated container logs. If the API key is empty or invalid, `/healthz` reports that the service is not configured and Compose marks it unhealthy.

## Start and operate

Run this from the repository root to start on the Mac's Wi-Fi interface. macOS Wi-Fi is not always `en0`; this reads the current IPv4 address from the `Wi-Fi` network service and starts or rebuilds the service:

```sh
MAC_LOCAL_IP="$(networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}')" \
  docker compose up -d --build
```

Check health from the Mac:

```sh
MAC_LOCAL_IP="$(networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}')"
curl --fail "http://${MAC_LOCAL_IP}:3000/healthz"
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

Changing application code requires rebuilding the image. Changing only `backend/.env` requires recreating the container with the current Wi-Fi address:

```sh
MAC_LOCAL_IP="$(networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}')" \
  docker compose up -d --force-recreate companion-api
```

To select another available model explicitly for the local service, set `COMPANION_LOCAL_MODEL` when recreating the container. Use the same value on later `docker compose up` commands so Compose keeps that model:

```sh
COMPANION_LOCAL_MODEL=gemini-3.8-flash \
  MAC_LOCAL_IP="$(networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}')" \
  docker compose up -d --force-recreate companion-api
```

## Connect the Android phone over local Wi-Fi

Keep the Mac powered on, Docker Desktop running, and the Mac and phone connected to the same private Wi-Fi network. Find the Mac's Wi-Fi IPv4 address with:

```sh
networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}'
```

If this prints no address, check the Wi-Fi connection details in macOS System Settings. The Wi-Fi device name can differ between Macs; querying the `Wi-Fi` network service avoids assuming the device is `en0`. The Compose startup command binds port `3000` only to this Wi-Fi address. Use the same address in the Android build, replacing `MAC_LOCAL_IP` with the address you found:

```sh
MAC_WIFI_IP="$(networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}')"
./gradlew assembleDebug -PcompanionBackendUrl="http://${MAC_WIFI_IP}:3000"
```

For example, the address has the form `http://192.168.x.x:3000`; do not place a real machine address in application source code. The equivalent environment-variable form is:

```sh
COMPANION_BACKEND_URL=http://MAC_LOCAL_IP:3000 ./gradlew assembleDebug
```

The Gradle property takes precedence when both settings are provided, and its value is embedded in that APK at build time. The `ConversationEngineFactory` reports a configuration error if the URL is empty; it does not silently select the static mock response. The provider key is never passed to Gradle or Android.

This HTTP setup is only for a trusted private LAN. It does not encrypt conversation text between the phone and Mac. Use a private Wi-Fi IPv4 address; do not use public Wi-Fi, configure router port forwarding, or expose port `3000` to the public internet. Docker binds the port to all interfaces if no host address is supplied, so this Compose file uses the selected Wi-Fi address and falls back to loopback if it is missing. If the Mac firewall blocks the phone, allow Docker Desktop or the local network connection in macOS Firewall settings. Router Wi-Fi client isolation can also prevent devices from reaching one another.

The address is available only while the Mac is powered on, connected to Wi-Fi, and running the Compose service. This setup does not provide access from outside the local network.

If the Mac receives a different Wi-Fi address, recreate the container using the updated address:

```sh
MAC_LOCAL_IP="$(networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}')" \
  docker compose up -d --force-recreate companion-api
```

## Diagnose Android and Gemini

Check the public configuration and send a synthetic request from the Mac. The last command calls the configured Gemini model and may use its quota:

```sh
MAC_WIFI_IP="$(networksetup -getinfo Wi-Fi | awk -F': ' '/^IP address:/{print $2}')"
curl --fail "http://${MAC_WIFI_IP}:3000/healthz"
curl --fail "http://${MAC_WIFI_IP}:3000/v1/config"
curl --fail "http://${MAC_WIFI_IP}:3000/v1/conversations" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Kısa bir bağlantı testi yanıtı ver.","language":"tr-TR","history":[]}'
```

The Android test APK includes a device probe that uses the same `ConversationEngine` as the animated face. Replace `MAC_LOCAL_IP` and `YOUR_ADB_SERIAL` before running these commands. The optional `conversation` argument sends one real Gemini request without microphone input or TTS:

```sh
./gradlew assembleDebug assembleDebugAndroidTest -PcompanionBackendUrl=http://MAC_LOCAL_IP:3000
PHONE_SERIAL=YOUR_ADB_SERIAL
adb -s "$PHONE_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$PHONE_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$PHONE_SERIAL" shell am instrument -w -e conversation true \
  com.companion.assistant.test/com.companion.assistant.BackendProbeInstrumentation
adb -s "$PHONE_SERIAL" logcat -d -v brief 'VoiceInteraction:V' '*:S'
docker compose logs --tail=50 companion-api
```

Compare `X-Request-ID` values in Logcat and Docker logs. Logs contain status and normalized error codes, not conversation text or API keys. A configuration probe alone omits `-e conversation true`. A real voice test still needs someone to tap the face and speak near the phone. By default, voice input uses the Android platform speech service with `tr-TR`; availability, network use, and offline Turkish support depend on the device's installed service. A separately configured HTTPS PCM recognition endpoint remains available.

`gemini-3.8-flash` is a valid Google model, but repeated local requests timed out while `gemini-3.5-flash-lite` completed a synthetic request quickly. The local Compose default therefore favors the tested faster model. See Google's [model list](https://ai.google.dev/gemini-api/docs/models) and [pricing/free-tier table](https://ai.google.dev/gemini-api/docs/pricing) for current availability and quota details.

## Portability

The Compose file builds the same `backend/Dockerfile` used by the Oracle Cloud deployment. The Node server listens on `0.0.0.0` inside the container; Compose binds the host port to the selected host address. On another Docker-compatible host, provide `MAC_LOCAL_IP` if remote LAN access is needed, provide a host-only environment file, and run `docker compose up -d --build`. Keep the Oracle-specific files under `deploy/oracle-cloud/` and `ORACLE_CLOUD_DEPLOYMENT.md` for a future VPS deployment; this local Compose setup does not alter them.
