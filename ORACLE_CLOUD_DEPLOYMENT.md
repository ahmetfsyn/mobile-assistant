# Oracle Cloud Ubuntu Deployment

This guide runs the existing provider-neutral Node.js API in Docker Compose, with Caddy as the only public web service. The API listens on port `3000` inside the Compose network; Docker does not publish that port on the host. Caddy obtains and renews HTTPS certificates after a real public DNS name and inbound networking are configured.

The current repository includes the deployment files, but no Oracle server deployment has been completed. The checked-in `companion.example.com` Caddy site is a placeholder and must be replaced before starting the stack.

## Requirements

- An Ubuntu Oracle Compute instance with outbound internet access.
- A public DNS name with an A record pointing to the instance's public IPv4 address. Add an AAAA record only if the instance and network are configured for IPv6.
- Oracle VCN security rules and Ubuntu firewall rules that allow inbound TCP `80` and `443`; TCP `443` is the Android API. UDP `443` is optional for HTTP/3.
- SSH access restricted to the administrator's known source CIDR.
- A Gemini API key from [Google AI Studio](https://aistudio.google.com/app/apikey).

Do not open the backend's port `3000` to the internet. Do not put a Gemini key in Git, an Android build property, a Dockerfile, a build argument, or a container image.

## 1. Inspect the server before changing it

Connect using your configured SSH alias, then confirm the OS, Docker installation, occupied ports, existing services, and intended deployment directory. Keep existing files and services until you have identified what owns them.

```sh
ssh <configured-ssh-alias>
lsb_release -ds
uname -m
sudo systemctl status docker caddy --no-pager || true
sudo docker ps -a
sudo ss -lntup
sudo ls -la /opt
sudo ls -la /etc/masa-arkadasi /etc/masa-arkadasi/caddy 2>/dev/null || true
```

If `/opt/masa-arkadasi` or `/etc/masa-arkadasi` already contains files, inspect and back them up before proceeding. The commands below use those paths as examples and never remove Docker volumes.

## 2. Install Docker Engine and Compose on Ubuntu

These commands use Docker's official Ubuntu apt repository. Review Docker's [Ubuntu installation guide](https://docs.docker.com/engine/install/ubuntu/) for supported Ubuntu releases and current package details.

```sh
sudo apt-get update
sudo apt-get install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker run hello-world
sudo docker compose version
```

Use `sudo docker ...` for administration. Membership in the `docker` group grants root-equivalent control of the host, so this guide does not add the login user to that group.

## 3. Clone the project and create server-only configuration

Create the deployment directory only after confirming it does not contain existing work:

```sh
if [ -e /opt/masa-arkadasi ]; then
  echo "/opt/masa-arkadasi already exists; inspect it before proceeding."
else
  sudo install -d -m 0755 -o "$USER" -g "$(id -gn)" /opt/masa-arkadasi
  git clone --single-branch --branch dev --depth 1 https://github.com/ahmetfsyn/mobile-assistant.git /opt/masa-arkadasi
fi
```

Create the host configuration directory and copy the examples only when the corresponding files do not already exist. The secret file lives outside the repository and Docker build context:

```sh
sudo install -d -o root -g root -m 0750 /etc/masa-arkadasi/caddy
sudo cp --no-clobber /opt/masa-arkadasi/deploy/oracle-cloud/.env.example /etc/masa-arkadasi/.env
sudo cp --no-clobber /opt/masa-arkadasi/deploy/oracle-cloud/caddy/Caddyfile /etc/masa-arkadasi/caddy/Caddyfile
sudo chown root:root /etc/masa-arkadasi/.env /etc/masa-arkadasi/caddy/Caddyfile
sudo chmod 0600 /etc/masa-arkadasi/.env
sudo chmod 0644 /etc/masa-arkadasi/caddy/Caddyfile
sudoedit /etc/masa-arkadasi/.env
sudoedit /etc/masa-arkadasi/caddy/Caddyfile
```

Set these backend values in `/etc/masa-arkadasi/.env`:

```dotenv
AI_PROVIDER=gemini
AI_MODEL=gemini-2.5-flash
GEMINI_API_KEY=your-real-key-from-google-ai-studio
PORT=3000
AI_TIMEOUT_MS=20000
API_RATE_LIMIT_PER_MINUTE=40
```

Enter the real key only in the `sudoedit` session. Do not put it in a shell command, terminal transcript, Caddy configuration, Compose file, or Git. The backend health endpoint reports `not_configured` until the required key is set.

Edit the first line of `/etc/masa-arkadasi/caddy/Caddyfile` to your real hostname, for example `companion.your-domain.example`. The hostname must resolve publicly to this instance. The example `Caddyfile` is mounted read-only from `/etc`, so server-specific changes stay outside the Git checkout.

## 4. Configure Oracle Cloud and Ubuntu ingress

In the Oracle Cloud console, open the instance VNIC's attached Network Security Group or subnet Security List. Add stateful ingress rules:

| Protocol | Destination port | Source | Purpose |
| --- | ---: | --- | --- |
| TCP | 80 | `0.0.0.0/0` | Caddy HTTP-to-HTTPS redirect and ACME HTTP challenge |
| TCP | 443 | `0.0.0.0/0` | HTTPS API for the phone |
| UDP | 443 | `0.0.0.0/0` | Optional Caddy HTTP/3 |
| TCP | 22 | Administrator's public IP `/32` | SSH administration |

Add matching IPv6 rules only when public IPv6 DNS and routing are configured. Do not add an ingress rule for port `3000`. Oracle recommends limiting administrative ingress to known sources; see [OCI network security guidance](https://docs.oracle.com/en-us/iaas/Content/Security/Reference/configuration_tasks.htm).

Inspect UFW before changing it. Preserve the current SSH path, add its restricted source rule before enabling the firewall, and adapt the port if SSH uses something other than 22:

```sh
sudo ufw status verbose
sudo ufw allow from <ADMIN_PUBLIC_CIDR> to any port 22 proto tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp  # optional HTTP/3
```

Only if UFW is inactive and you have confirmed the SSH rule is correct, enable it:

```sh
sudo ufw enable
sudo ufw status verbose
```

These are instructions for the server administrator; no OCI or host firewall changes are made by the project files.

## 5. Start the services and verify HTTPS

From the checked-out repository:

```sh
cd /opt/masa-arkadasi/deploy/oracle-cloud
sudo docker compose config --quiet
sudo docker compose up -d --build
sudo docker compose ps
sudo docker compose logs --tail=100 companion-api caddy
```

The API runs as the unprivileged `node` user with a read-only root filesystem, dropped Linux capabilities, a bounded temporary filesystem, and a health check against `/healthz`. SQLite is stored at `/app/data/memories.sqlite` in the persistent `companion_data` named volume; it survives API image rebuilds. Caddy also runs as an unprivileged UID; it receives only the bind capability needed for ports 80/443. Both services restart with Docker after host reboots unless explicitly stopped. Caddy certificate/configuration and companion memory data are kept in named Docker volumes.

Docker's rotating `local` log driver retains container stdout/stderr on the host (up to five 10 MB files per service). The API logs startup and fatal listener errors; it does not log API keys, message bodies, or per-request content. Caddy's normal operational and TLS errors are available through Compose logs.

After DNS propagation and certificate issuance, verify the configuration and make a real conversation request:

```sh
curl --fail https://companion.your-domain.example/healthz
curl --fail https://companion.your-domain.example/v1/config
curl --fail https://companion.your-domain.example/v1/conversations \
  -H 'Content-Type: application/json' \
  -d '{"message":"Merhaba, bugün ne keşfedelim?","language":"tr-TR","history":[]}'
```

The last command tests live Gemini only when the server has a valid key and the selected model is enabled for that Google AI Studio project. Automated backend tests use mocked provider responses and cannot establish live connectivity.

## 6. Point Android to the HTTPS endpoint

`app/build.gradle.kts` already accepts `-PcompanionBackendUrl=...` or the `COMPANION_BACKEND_URL` environment variable. The URL is a public endpoint, not a credential. An empty URL keeps the existing local mock-response development fallback. Build the APK with the real hostname after HTTPS is verified:

```sh
cd /path/to/mobile-assistant
./gradlew assembleDebug -PcompanionBackendUrl=https://companion.your-domain.example
```

Or:

```sh
COMPANION_BACKEND_URL=https://companion.your-domain.example ./gradlew assembleDebug
```

The Gemini key is never part of either command or the APK. Changing `AI_MODEL` or rotating the API key does not require rebuilding Android; changing the backend hostname does.

## Updates, restart, recovery, and key rotation

Update to the latest `dev` commit, rebuild the API image, and apply the Compose changes:

```sh
cd /opt/masa-arkadasi
git status --short
git fetch origin
git switch dev
git pull --ff-only origin dev
cd deploy/oracle-cloud
sudo docker compose up -d --build
sudo docker compose ps
```

Server environment and Caddy files under `/etc/masa-arkadasi` are not replaced by Git updates. Use `sudo docker compose logs -f --tail=100 companion-api caddy` for troubleshooting. Restart the stack with `sudo docker compose restart`; after a host reboot, check `sudo systemctl status docker` and `sudo docker compose ps`. Docker is enabled at boot and both services use `restart: unless-stopped`.

To rotate the Gemini key, create a replacement in Google AI Studio, update only `GEMINI_API_KEY` with `sudoedit /etc/masa-arkadasi/.env`, then recreate the API container and verify health before revoking the old key:

```sh
cd /opt/masa-arkadasi/deploy/oracle-cloud
sudo docker compose up -d --force-recreate companion-api
sudo docker compose ps
curl --fail https://companion.your-domain.example/healthz
```

Back up the `companion_data` volume before host migration. Do not use `docker compose down -v`; that would remove the companion memory database and Caddy's persistent certificate volumes.

## If there is no public domain

Do not serve the API over plain HTTP. Caddy's local/IP certificates are not trusted by a normal Android `HttpURLConnection` client unless the issuing CA is installed on the phone, and this app does not install a custom CA. Caddy's public automatic HTTPS setup expects a real hostname and externally reachable ports 80/443; see [Caddy automatic HTTPS requirements](https://caddyserver.com/docs/automatic-https).

A private alternative is Tailscale Serve with a `*.ts.net` HTTPS name; it requires the VM and phone to be in the same tailnet and HTTPS certificates enabled. The current Tailscale Android app requires Android 8.0 or later, while this companion's target GM 5 Plus runs Android 7/API 24, so that option is not compatible with the current phone. See [Tailscale HTTPS certificates](https://tailscale.com/docs/how-to/set-up-https-certificates) and [Android support](https://tailscale.com/docs/install/android). For this device, obtain a public domain for Caddy or establish another Android-7-compatible private HTTPS setup with a certificate chain trusted by the phone. Until then, keep the API private and leave the Android backend URL unset.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| API container is unhealthy | `sudo docker compose logs --tail=100 companion-api`; confirm `/etc/masa-arkadasi/.env` has `AI_PROVIDER=gemini`, a non-empty key, and `PORT=3000`. Do not print the environment file. |
| Caddy cannot issue a certificate | Check the real hostname in `/etc/masa-arkadasi/caddy/Caddyfile`, public DNS A/AAAA records, OCI ingress, UFW, outbound connectivity, and `sudo docker compose logs --tail=100 caddy`. |
| `502` from Caddy | Check `sudo docker compose ps` and the API health status; port 3000 should remain internal to Compose. |
| `missing_provider_credentials` | Set `GEMINI_API_KEY` in the protected server `.env` and recreate the API service. |
| `unsupported_model` | Set `AI_MODEL` to a model available to the selected Google AI Studio project, then recreate the API container. No automatic fallback is used. |
| `rate_limited` or `quota_exceeded` | Check the project's Google AI Studio quota and wait for limits to reset or adjust usage. |
| Port already in use | Inspect `sudo ss -lntup` and existing services before stopping or replacing anything. |

See the [backend README](backend/README.md) for provider configuration, API contracts, and test coverage.
