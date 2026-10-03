# AWS deployment — #1157 S2

One Ubuntu 24.04 arm64 `t4g.small` in `us-east-2` runs the committed [Compose stack](../compose/docker-compose.yml). Caddy terminates TLS; PostgreSQL lives on a separate encrypted 20 GiB gp3 data volume. The Elastic IP survives instance replacement. Shell access is SSM Session Manager only: no SSH key pair or port 22. CPU credits use **standard**, so exhausted credits throttle instead of generating unlimited-credit charges.

## Prerequisites (FINAL-PLAN §F)

Complete the agreed FINAL-PLAN §F account setup: root MFA, an IAM Identity Center administrator, and `aws configure sso` / `aws sso login`. Use that SSO administrator profile for Terraform, with permission to manage the resources here, pass the instance/Budgets roles, and access billing and Cost Explorer. Root is for account recovery and the deliberately root-only backup retention exception, not routine deployment. Install Terraform >= 1.10 (the project uses 1.16), AWS CLI v2, the Session Manager plugin, and OpenSSL on the operator workstation.

The manually configured budgets are now codified here: delete the duplicates or import them into `aws_budgets_budget.monthly` and `aws_budgets_budget.hard_ceiling` before applying. Enable Cost Explorer. If an AWS services anomaly monitor already exists, import it into `aws_ce_anomaly_monitor.services`; AWS permits only one such dimensional monitor per account. Likewise, import an existing GitHub OIDC provider into `aws_iam_openid_connect_provider.github` instead of creating a duplicate. Review any existing multi-region CloudTrail to avoid duplicate management-event costs.

The account needs a default VPC, its default subnet in the first available AZ, and an internet gateway/default route. This module reads them; it does not recreate them. Security rules include IPv4 and IPv6, but this deployment publishes only an IPv4 A record; it does not add IPv6 addressing/routing to the default VPC. The source repository and GHCR image must be publicly readable from the host. The image must include `linux/arm64`, as produced by [image.yml](../../.github/workflows/image.yml).

All taggable AWS resources inherit `project = "dashbuddy-census"` and `managed_by = "terraform"` from provider `default_tags`. AWS attachment/configuration resources that do not support tags inherit their parent resource's context.

## 1. Bootstrap the state bucket once

From the repository root, with the SSO profile selected:

```sh
export AWS_PROFILE=<your-sso-admin-profile>
aws sso login
cd deploy/aws/bootstrap
terraform init
terraform apply
terraform output -raw state_bucket
```

Keep bootstrap's local state in a secure backup; it is intentionally independent of the main deployment. Its versioned, SSE-S3 encrypted, private bucket is protected against accidental Terraform destruction. There is no state expiry policy.

```sh
cd ..
terraform init \
  -backend-config="bucket=<name-from-bootstrap>" \
  -backend-config="key=census/terraform.tfstate" \
  -backend-config="region=us-east-2" \
  -backend-config="use_lockfile=true"
```

Native S3 locking uses `census/terraform.tfstate.tflock`; no DynamoDB table is needed. The operator needs `s3:ListBucket`, state `GetObject`/`PutObject`, and lockfile `GetObject`/`PutObject`/`DeleteObject`. Never put credentials in backend arguments. Commit both generated `.terraform.lock.hcl` files after initialization; state, `.terraform/`, and real `.tfvars` files are ignored. Treat the state bucket and bootstrap state as sensitive: Terraform may refresh real SecureString values into state even with `ignore_changes`.

## 2. Apply

```sh
terraform apply -var='alert_email=you@example.com'
terraform output
```

For reproducibility, set `compose_ref` to a reviewed branch/tag of **this repository** and `image_ref` to a scanned, signature-verified `ghcr.io/sjtrotter/dashbuddy-census@sha256:<digest>`. `git clone --branch` supports branches/tags, not bare commit SHAs. Persist non-secret settings in a local `.tfvars` file and supply it on later applies. `alert_email` has no default. Confirm the SNS subscription email, and review any Budgets/Cost Anomaly subscription confirmation emails.

Cloud-init installs Docker from Docker's arm64 apt repository, AWS CLI v2, the CloudWatch agent, and the Compose files under `/opt/census`. Its first start intentionally fails while parameters contain `CHANGE-ME`; infrastructure creation still completes. Boot diagnostics are in `cloud-init-output.log` and `journalctl -u census`. Wait for installation to finish before restarting the service below. The CloudWatch agent emits only memory and root-filesystem metrics, aggregated by InstanceId every five minutes. The 14-day host log group is reserved: **no host or app/container logs are shipped in S2**; the instance has no CloudWatch Logs write permissions.

## Data durability

The encrypted gp3 data volume has `prevent_destroy = true` and mounts by UUID at `/var/lib/census-data`. PostgreSQL uses `/var/lib/census-data/pgdata`; local dumps use `/var/lib/census-data/backups`; pending alarm copies use `/var/lib/census-data/alarms` (0700, uid/gid 10001). Rebuilding the instance re-attaches this same volume in the same AZ. Terraform refuses to destroy it, and the disposable root volume holds no durable database data. Within this instance-replacement lifecycle, the only way to lose the retained volume is to delete it by hand or delete the account; backups in S3 are the second copy for recovery from database corruption or accidental data deletion.

The instance ignores changes to its AMI and user data (including the base64 template attribute), so a new Canonical "current" AMI or a changed template never replaces the box by itself. Host patching uses `unattended-upgrades`. To deliberately rebuild with the current AMI and template, take and verify a fresh backup, then run from `deploy/aws` with your usual variable settings:

```sh
terraform apply -replace=aws_instance.census
```

This stops the old instance before detaching the data volume and re-attaches it to the replacement. Cloud-init formats only a volume with no filesystem, preserves existing data, and waits for attachment before starting Compose. Refresh the GitHub instance variable after the rebuild. Routine image promotion uses the workflow.

## 3. Set parameters and resume startup

The three SecureStrings use the AWS-managed `aws/ssm` key; the instance has scoped SSM reads and `kms:Decrypt` only on that key ARN, resolved through `alias/aws/ssm`, with no customer KMS key or broad KMS grant. Terraform creates placeholders and ignores later value changes. ACME email is an operator-owned String; `public_host` is a String continuously managed from `var.public_host`, and `alerts_topic_arn` is a String managed from the existing SNS topic. The optional TOTP secret is provisioned below.

Run on the workstation (adjust the prefix if customized; disable shell tracing):

```sh
export AWS_DEFAULT_REGION=us-east-2
aws ssm put-parameter --name /dashbuddy-census/postgres_password \
  --type SecureString --value "$(openssl rand -base64 32)" --overwrite
aws ssm put-parameter --name /dashbuddy-census/acme_email \
  --type String --value 'you@example.com' --overwrite
```

Generate a high-entropy operator token in your password manager (or use `openssl rand -hex 32` privately), and retain the **original token only in the password manager**. Store only its SHA-256 digest. The following Bash reads the retained token without displaying it or placing it in shell history:

```bash
read -r -s -p 'Operator token from password manager: ' OPERATOR_TOKEN
printf '\n'
TOKEN_SHA256=$(printf '%s' "$OPERATOR_TOKEN" | openssl dgst -sha256 -r | cut -d ' ' -f 1)
aws ssm put-parameter --name /dashbuddy-census/operator_token_sha256 \
  --type SecureString --value "$TOKEN_SHA256" --overwrite
unset OPERATOR_TOKEN TOKEN_SHA256
```

The host writes a root-owned 0600 `.env` atomically, never logs secret values, and refuses missing/placeholder **required** parameters (`public_host`, `acme_email`, `postgres_password`, `operator_token_sha256`). Database passwords must use the documented base64 format (letters, digits, `+ / = _ -`); token hashes must have 64 hex digits. `POSTGRES_PASSWORD` and `DATABASE_PASSWORD` get the same value. Changing the SSM database password does not rotate an existing PostgreSQL role: coordinate the database password change before recreating containers.

## Operator second factor + alarm delivery

1. Generate the TOTP secret **locally**, with shell tracing disabled. Retain it in the password manager and in `~/dashbuddy/secrets/census-operator-totp` with mode 0600. Generate only once; rotation requires enrolling the replacement and restarting the service.

   ```sh
   umask 077
   mkdir -p ~/dashbuddy/secrets
   (set -C; python3 -c 'import secrets,base64; print(base64.b32encode(secrets.token_bytes(20)).decode())' > ~/dashbuddy/secrets/census-operator-totp)
   chmod 0600 ~/dashbuddy/secrets/census-operator-totp
   ```

   Enrol the retained secret in an authenticator using this URI (substitute privately; this is text, not a request to a website):

   ```text
   otpauth://totp/dashbuddy-census:operator?secret=<SECRET>&issuer=dashbuddy-census&algorithm=SHA1&digits=6&period=30
   ```

   A local QR is optional via `qrencode -t ANSIUTF8`; treat the URI and QR as the secret itself. Never paste them into tickets, logs, or an SSM command. After step 2 creates the placeholder, store the retained secret from the workstation:

   ```sh
   aws ssm put-parameter --name /dashbuddy-census/operator_totp_secret \
     --type SecureString --value "$(cat ~/dashbuddy/secrets/census-operator-totp)" --overwrite
   ```

2. From `deploy/aws`, with the usual profile, region, and variable file, run the operator checks and apply:

   ```sh
   terraform fmt -check
   terraform validate
   terraform plan
   terraform apply
   ```

   This adds the `operator_totp_secret` SecureString placeholder, the instance role's `sns:Publish` grant scoped to `aws_sns_topic.alerts.arn`, and the Terraform-managed `alerts_topic_arn` String. The existing SSM read resource `/dashbuddy-census/*` covers both names. Now run the workstation `put-parameter` above; never put the real TOTP secret in Terraform configuration. Confirm the existing SNS email subscription. Deploy the S6b app image through the usual image workflow before restarting below.

   The renderer omits `OPERATOR_TOTP_SECRET` or `ALERTS_TOPIC_ARN` when its optional parameter is missing, empty, or `CHANGE-ME`; startup continues. Present values must match `^[A-Z2-7]{16,64}$` and `^arn:aws:sns:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]{1,256}$` respectively. Invalid formats refuse rendering without printing values; the app also validates canonical base32. An omitted TOTP secret leaves authenticated mutations at `503 totp_unconfigured`; an omitted topic makes the host publisher exit silently; the app still logs and keeps its bounded spool. The four required parameters and their refusal are unchanged.

3. **Existing host:** its cloud-init is frozen by `ignore_changes`, so applying the template edit does **not** update the host scripts or units. Run this one-time SSM command from the workstation's reviewed S6b checkout in `deploy/aws`. It installs the exact optional-parameter renderer, startup script, census service, and alarm publisher script plus its service/path/timer units from the template, with root ownership and 0700 scripts. Set `CENSUS_NAME_PREFIX` and `CENSUS_IMAGE_REF` to this deployment's non-secret template settings, and `CENSUS_COMPOSE_REF` to the reviewed branch/tag containing these fixes. Bootstrap originally cloned the repository to `/opt/census-src` and copied Compose into `/opt/census`; neither `census-start` nor the image deploy workflow refreshes those files. This command holds the deployment lock, fetches the reviewed Compose file, preserves the selected census image and existing volume bindings, creates the alarms directory owned by uid/gid 10001, initializes the root-owned 0600 budget state outside the spool (preserving any existing budget) and root-owned `/root/.aws`, and writes the alarm volume bind into the Compose override. It enables the publisher path/timer and restarts `census.service`.

   ```bash
   set -euo pipefail
   export AWS_DEFAULT_REGION="$(terraform output -raw region)"
   export AWS_PAGER=''
   export CENSUS_NAME_PREFIX='dashbuddy-census'
   export CENSUS_IMAGE_REF='ghcr.io/sjtrotter/dashbuddy-census:latest' # use your image_ref value
   export CENSUS_COMPOSE_REF='feature/s6b-totp-sns' # reviewed branch/tag containing these fixes
   CENSUS_INSTANCE_ID=$(terraform output -raw instance_id)
   umask 077
   CENSUS_RENDER_COMMAND=$(mktemp)
   trap 'rm -f -- "$CENSUS_RENDER_COMMAND"' EXIT
   python3 - > "$CENSUS_RENDER_COMMAND" <<'PY'
   import base64
   import json
   import os
   import re
   import shlex
   import textwrap
   from pathlib import Path

   template = Path('cloud-init.yaml.tftpl').read_text()
   settings = {
       'region': (os.environ['AWS_DEFAULT_REGION'], r'[a-z0-9-]+'),
       'name_prefix': (os.environ['CENSUS_NAME_PREFIX'], r'[a-z0-9-]+'),
       'image_ref': (os.environ['CENSUS_IMAGE_REF'], r'ghcr\.io/[A-Za-z0-9_./:@-]+'),
   }
   for name, (value, pattern) in settings.items():
       if not re.fullmatch(pattern, value):
           raise SystemExit('Invalid non-secret template setting: ' + name)
   compose_ref = os.environ['CENSUS_COMPOSE_REF']
   if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_./-]*', compose_ref):
       raise SystemExit('Invalid compose ref')
   command = [
       'set -eu',
       'umask 077',
       'cd /opt/census',
       'temporary=$(mktemp -d /opt/census/.s6b-upgrade.XXXXXX)',
       'trap \'rm -rf -- "$temporary"\' EXIT',
       """image=$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["census"]["image"])')""",
       'git -C /opt/census-src fetch --depth 1 origin ' + shlex.quote(compose_ref),
       'git -C /opt/census-src show FETCH_HEAD:deploy/compose/docker-compose.yml > "$temporary/docker-compose.yml"',
       r'sed -i "/^    census:/,/^    [a-zA-Z0-9_-]*:/s#^\([[:space:]]*image:[[:space:]]*\).*#\1$image#" "$temporary/docker-compose.yml"',
   ]
   # Compose emits resolved volume definitions as JSON (also valid YAML). Retain
   # pgdata, including a recovery-host override, while adding the alarm bind.
   volume_script = (
       'import json,sys; volumes=json.load(sys.stdin)["volumes"]; '
       'volumes["alarm-spool"]={"driver":"local","driver_opts":'
       '{"type":"none","o":"bind","device":"/var/lib/census-data/alarms"}}; '
       'print(json.dumps({"volumes":volumes}))'
   )
   command.append('docker compose config --format json | python3 -c ' + shlex.quote(volume_script)
                  + ' > "$temporary/docker-compose.override.yml"')
   installs = []
   for path, mode in [
       ('/usr/local/sbin/census-configure', '0700'),
       ('/usr/local/sbin/census-start', '0700'),
       ('/usr/local/sbin/census-alarm-publish', '0700'),
       ('/etc/systemd/system/census-alarm-publish.service', '0644'),
       ('/etc/systemd/system/census-alarm-publish.path', '0644'),
       ('/etc/systemd/system/census-alarm-publish.timer', '0644'),
       ('/etc/systemd/system/census.service', '0644'),
   ]:
       block = template.split('  - path: ' + path + '\n', 1)[1].split('\n  - path:', 1)[0]
       content = textwrap.dedent(block.split('    content: |\n', 1)[1]).rstrip() + '\n'
       for name, (value, _) in settings.items():
           content = content.replace('${' + name + '}', value)
       if '${' in content:
           raise SystemExit('Unresolved template setting in ' + path)
       encoded = base64.b64encode(content.encode()).decode()
       staged = '"$temporary/' + Path(path).name + '"'
       command.extend([
           'base64 --decode > ' + staged + " <<'CENSUS_FILE'",
           encoded,
           'CENSUS_FILE',
       ])
       if path == '/usr/local/sbin/census-alarm-publish':
           command.append('python3 -c ' + shlex.quote(
               'import ast,sys; ast.parse(open(sys.argv[1]).read())') + ' ' + staged)
       elif mode == '0700':
           command.append('bash -n ' + staged)
       installs.append('install -o root -g root -m ' + mode + ' ' + staged + ' ' + path)
   # Reuse the exact root-only initialization fragment from data setup; do not
   # rerun its volume formatting/mount steps on an existing host.
   setup_block = template.split('  - path: /usr/local/sbin/census-data-setup\n', 1)[1].split('\n  - path:', 1)[0]
   setup = textwrap.dedent(setup_block.split('    content: |\n', 1)[1])
   publisher_setup = '# Root-only publisher state;' + setup.split('# Root-only publisher state;', 1)[1]
   command.extend([
       'for unit in census-alarm-publish.path census-alarm-publish.timer census-alarm-publish.service; do '
       'if systemctl cat "$unit" >/dev/null 2>&1; then systemctl stop "$unit"; fi; done',
       *installs,
       'install -d -m 0700 -o 10001 -g 10001 /var/lib/census-data/alarms',
       publisher_setup,
       'install -o root -g root -m 0600 "$temporary/docker-compose.override.yml" /opt/census/docker-compose.override.yml',
       'install -o root -g root -m 0600 "$temporary/docker-compose.yml" /opt/census/docker-compose.yml',
       'systemctl daemon-reload',
       'systemctl enable --now census-alarm-publish.path census-alarm-publish.timer',
       'systemctl restart census.service',
   ])
   locked = 'mountpoint -q /var/lib/census-data && flock -w 600 /var/lib/census-data/.deploy.lock sh -c ' + shlex.quote('\n'.join(command))
   print(json.dumps({'commands': [locked], 'executionTimeout': ['900']}))
   PY
   CENSUS_COMMAND_ID=$(aws ssm send-command \
     --instance-ids "$CENSUS_INSTANCE_ID" \
     --document-name AWS-RunShellScript \
     --parameters "file://$CENSUS_RENDER_COMMAND" \
     --timeout-seconds 300 \
     --query Command.CommandId --output text)
   aws ssm wait command-executed --command-id "$CENSUS_COMMAND_ID" --instance-id "$CENSUS_INSTANCE_ID"
   aws ssm get-command-invocation --command-id "$CENSUS_COMMAND_ID" --instance-id "$CENSUS_INSTANCE_ID" \
     --query Status --output text
   ```

   If the CLI waiter expires before the service restart finishes, query status again before resubmitting. A **new instance gets these scripts, units, and Compose settings automatically from cloud-init**. After the one-time replacement, future parameter changes need only `systemctl restart census.service`; Compose already passes `.env` to Census via `env_file`.

Application alarm delivery is WARN log + spool file → host publisher → the existing SNS email subscription. The WARN under `Alarm` is the system of record. Compose sets `ALARM_SPOOL_DIR=/var/spool/census-alarms` and mounts the `alarm-spool` named volume there; the host override binds it to `/var/lib/census-data/alarms`. `FileSpoolAlarmSink` writes exactly the validated rendering to a temporary file and atomically renames it to `.alarm`, dropping the oldest at the 64-file cap. An I/O failure increments process-local `AlarmStats.spool_failed` and produces at most one `Alarm` WARN `alarm_spool_failed class=<simple name>` per ten minutes, without failing health admission or purge. Unset `ALARM_SPOOL_DIR` selects logging only.

The root Python publisher treats the spool as hostile: it opens entries relative to a pinned directory fd with `O_NOFOLLOW`, checks the opened inode is regular, uid 10001, single-link and at most 4 KiB, and strictly validates UTF-8 and the five-line grammar. Numeric `<epochMillis>-<counter>.alarm` names are required. Other names (including hidden and temporary files), directories, symlinks, hard links, wrong-owner/oversized files and malformed messages are rejected and removed without following links. It retains at most the newest 256 valid entries during each scan and attempts at most 16 oldest first per run. A persistent root-owned token bucket outside the spool has capacity 20 and refills at 20/hour; each attempt consumes a token before AWS runs, including failures. Deferred valid files remain until delivery or storage eviction. A corrupt budget state fails closed and requires operator repair; upgrades preserve it.

Each AWS CLI publish has a 20-second timeout; success deletes the entry, failure retains it. The hardened service has a five-minute timeout, a read-only system except the spool, budget state and root AWS cache, private temporary storage and no new privileges. It journals only `census-alarm-publish: published=<n> failed=<n> rejected=<n> deferred=<n>` plus one `census-alarm-publish: budget exhausted, deferred=<n>` when needed, never names, bodies, exception text or AWS output. Missing topic configuration exits silently. Inspect `journalctl -u census-alarm-publish.service`; the path unit watches directory changes and the timer retries every five minutes.

The subject is `census alarm: <kind>`; the body has exactly five lines: `kind=...`, `platform=...`, `version=...`, `install_prefix=...`, `rule_ids=...`. The host accepts `[redacted]` only for platform/version; the prefix must be eight lowercase hex characters or `-`, and rule IDs must match the wire grammar. Neither the application message nor its logs contain a topic ARN/account ID, full install ID, secret, token, TOTP code, payload string, or exception message. AWS's own email envelope/unsubscribe links are outside the application message. Spool overflow or write failure may lose the email copy, and a successful publish followed by a host crash before deletion can cause a duplicate. Counts of raised alarms refer to local delivery, not confirmed email delivery.

## 4. DNS and first start

Create the DNS **A** record `census.dashbuddy.trotter.cloud` pointing to `terraform output -raw elastic_ip` (or use the configured `public_host`). Allow DNS to propagate so Caddy can complete ACME validation.

```sh
aws ssm start-session --target "$(terraform output -raw instance_id)"
```

In that session:

```sh
sudo cloud-init status --wait
# An error is expected on the first boot if CHANGE-ME was still present.
sudo systemctl restart census.service
sudo systemctl status census.service --no-pager
sudo systemctl list-timers census-backup.timer
```

No cloud-init rerun or instance replacement is necessary for setting parameters. `census.service` refreshes `.env` on subsequent starts; compose image selections survive restarts. `CENSUS_IMAGE_TAG` is derived from `image_ref` (a digest uses the unused fallback `latest`). Cloud-init rewrites only the census `image:` line with `sed`, including digest form, because `@sha256:...` cannot be substituted into the original colon/tag expression.

Security updates run through unattended-upgrades; required reboots occur at 04:30 UTC. The persistent backup timer runs at 03:30 UTC, executes the committed `backup.sh` with `BACKUP_BUCKET` and `BACKUPS_DIR=/var/lib/census-data/backups`, then removes local dumps older than two days. The script itself uses `./backups`; the wrapper runs in `/opt/census`, where `backups` is a symlink to the data volume. Inspect `journalctl -u census-backup` and periodically verify uploads; no backup-failure alarm is included yet.

## 5. Verify

```sh
terraform output
curl --fail https://census.dashbuddy.trotter.cloud/healthz
curl --fail https://census.dashbuddy.trotter.cloud/readyz
curl --fail https://census.dashbuddy.trotter.cloud/v1/policy
```

In SSM, run `sudo systemctl start census-backup.service` and inspect its journal. With the workstation SSO credentials, list the backup bucket to confirm today's object. Check that all five CloudWatch alarms receive data (memory and disk can take several minutes; the agent heartbeat alarms when memory metrics stop arriving), the SNS subscription is confirmed, CloudTrail is delivering files, and the budget stop action is enabled and targets the current instance.

## 6. Deploy an image

Set all three GitHub repository variables from `deploy/aws` (the `github_variables` output also exposes them as a map):

```sh
gh variable set AWS_DEPLOY_ROLE_ARN --body "$(terraform output -raw github_deploy_role_arn)"
gh variable set CENSUS_INSTANCE_ID --body "$(terraform output -raw instance_id)"
gh variable set AWS_REGION --body "$(terraform output -raw region)"
```

The workflow uses `AWS_REGION`, falling back to `us-east-2`. Run **Deploy AWS** manually from `main` or a `v*` tag, specifying the reviewed image digest. The role trusts only those refs and uses GitHub OIDC, with no stored AWS access key. AWS now validates GitHub using trusted root CAs, so [thumbprints are omitted](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_openid_connect_provider).

This repository was created on 2026-10-01. GitHub's OIDC documentation specifies that repositories created after July 15, 2026 use `repo:OWNER@OWNER-ID/REPO@REPO-ID`; name-only subjects cannot be minted for this repository. Set `github_owner_id` and `github_repository_id` to the immutable IDs when using another repository. Find them with:

```sh
gh api repos/<owner>/<repo> --jq .id
gh api users/<owner> --jq .id
```

The workflow serializes production deployments and acquires `/var/lib/census-data/.deploy.lock` on the host (waiting up to 600 seconds). It validates/quotes input, replaces only the census image, pulls, and runs `docker compose up -d --wait --wait-timeout 180`. Limits to know: SSM's delivery window is the SUM of `--timeout-seconds` (300) and `executionTimeout` (900), so a command the agent could not receive may still be delivered up to 20 minutes after submission; the workflow issues `cancel-command` on failure or cancellation, but AWS does not guarantee cancellation terminates a running script, and there is no submission-time freshness check — a late command could apply an older `image_ref` after a newer one. Re-run the newest deploy if two overlapped. On failure the workflow prints the last 50 lines of the census container log into the Actions log; the server logs counts and reason codes only (see `docs/SECURITY.md`), never request bodies or secrets.. Both the workflow and cloud-init then check `/readyz` for at most 60 seconds using `docker compose exec -T census wget -qO- http://127.0.0.1:8080/readyz`, since census publishes no host port. Failure prints the last 50 census log lines and exits non-zero. SSM has a 300-second delivery timeout and 900-second execution timeout; polling is bounded to 1,200 seconds and cancellation/failure cancels the command. Recheck the public endpoints after deployment to verify external routing and TLS. To roll back, dispatch an earlier compatible digest; consult the [operator migration guidance](../../docs/OPERATOR.md). Update `CENSUS_INSTANCE_ID` after every replacement. The workflow does not refresh the committed Compose files or Parameter Store values.

SSM `AWS-RunShellScript` runs as root. Protect trusted repository refs and workflow edits accordingly; the narrowly scoped AWS role still has administrative access to this one host.

## Restore drill / disaster recovery

Run a drill on a new isolated recovery box provisioned with this cloud-init, or perform these steps on the replacement box before moving DNS. Keep the original box/data until the restored system passes verification. The host role can upload/list backups but deliberately cannot read or delete them; use the operator's SSO identity to select and download a backup. On the workstation:

```sh
aws s3 ls s3://<backup-bucket>/
aws s3 presign s3://<backup-bucket>/census-YYYY-MM-DD.sql.gz --expires-in 600
```

Treat the short-lived URL as a credential. In an SSM session on the **recovery box**, become root with `sudo -i`. Stop the app and choose a fresh volume even if first boot already ran migrations; `restore.sh` correctly refuses initialized databases. The commands below preserve the existing volume and do not use `down -v`:

```bash
set -euo pipefail
umask 077
systemctl stop census-backup.timer
systemctl stop census.service
cd /opt/census
/usr/local/sbin/census-configure
docker compose down
# A fresh directory and volume name preserve the current data for this drill.
mountpoint -q /var/lib/census-data
RESTORE_VOLUME="census-restored-$(date -u +%Y%m%d%H%M%S)"
RESTORE_DIR="/var/lib/census-data/$RESTORE_VOLUME"
mkdir -m 0700 "$RESTORE_DIR"
chown 70:70 "$RESTORE_DIR"
cat > docker-compose.override.yml <<YAML
volumes:
    alarm-spool:
        driver: local
        driver_opts:
            type: none
            o: bind
            device: /var/lib/census-data/alarms
    pgdata:
        name: $RESTORE_VOLUME
        driver: local
        driver_opts:
            type: none
            o: bind
            device: $RESTORE_DIR
YAML
docker compose up -d --wait postgres
read -r -s -p 'Short-lived backup URL: ' BACKUP_URL
printf '\n'
curl --fail --silent --show-error "$BACKUP_URL" -o /opt/census/restore.sql.gz
unset BACKUP_URL
./restore.sh /opt/census/restore.sql.gz
# Type RESTORE at the prompt. Review restored data and any pending migrations.
systemctl start census.service
systemctl start census-backup.timer
docker compose ps
rm /opt/census/restore.sql.gz
```

Check readiness, policy, relevant row counts, and backup upload from the recovered database. Reapply later withdrawals when that feature exists, as described in [OPERATOR.md](../../docs/OPERATOR.md). Record the backup timestamp, recovered coverage, and elapsed recovery time. Only then move DNS/EIP for real recovery; for a drill, leave production DNS unchanged. Preserve the bind-directory and volume-name override for subsequent deployments on the recovered host; before another instance rebuild, promote the recovered directory to `/var/lib/census-data/pgdata` with the stack stopped and the prior directory retained. Refresh GitHub's instance variable after replacement. Retire recovery resources and retained volumes deliberately after verification.

### Recovery box by hand, and running the drill over SSM

A recovery box needs no Terraform change: create a scratch gp3 data volume in the instance's AZ, render the template with `terraform console` (`templatefile("cloud-init.yaml.tftpl", {… data_volume_id = "<scratch volume>"})`, the other settings as deployed), **gzip the rendered YAML** (`aws ec2 run-instances` refuses user data above 16 KiB raw; cloud-init accepts gzip), launch with the production AMI, subnet, security group and instance profile and IMDSv2 required, then `attach-volume` it at `/dev/sdf`. Three things the shared configuration makes the recovery box do that it must not:

- it starts Caddy for the production hostname, whose DNS points at the production box, so every ACME attempt fails and counts against Let's Encrypt's per-hostname failure limit — `docker compose stop caddy` as soon as the stack is up (a `docker ps` watcher arms within seconds of SSM coming online);
- cloud-init's last step re-enables `census-backup.timer`, which would upload the recovery box's dump over the production object at 03:30 UTC — `systemctl disable --now census-backup.timer` **after** cloud-init reports `done`, not before;
- its alarm publisher is live (an empty database raises none).

Over `send-command` the confirmation prompt is answered with `printf 'RESTORE\n' | ./restore.sh …` and the presigned URL arrives shell-quoted as an argument; it is then visible in SSM command history for the 600 s it lives — acceptable for a drill, not for a real recovery, where the SSM-session `read -r -s` form above keeps it out of any log. The override written by cloud-init is the YAML above; the S6b upgrade rewrites it as the JSON `docker compose config` emits (also valid YAML) — a drill that regenerates the override through `docker compose config --format json` works on either shape. Terminate the box and delete the scratch volume when the record below is written; nothing else is left behind.

### Drill log

- **2026-10-03 (first drill, DashBuddy #1179).** Backup `census-2026-10-03.sql.gz` (26,711 bytes, taken 13:36:24 UTC by `census-backup.service` on demand, containing 1 trusted install, 60 clusters / samples / sightings, 88 token sightings, 1 ledger row, plus a scratch install enrolled for the test and its nonce). Recovery box: t4g.small from the production AMI + this cloud-init, scratch volume, launched 13:37:33, cloud-init done 13:43:38 (6 min; Caddy stopped by the watcher, backup timer disabled after boot). Restore over SSM with the regenerated-override form: **20 s** from `compose stop` to a local backup taken from the recovered database (26,724 bytes); `/readyz` ok, `/v1/policy` serverVersion v0.6.0, Flyway at version 1, every table count equal to the pre-withdrawal production counts, the scratch install and its nonce present. **Post-backup withdrawal:** the scratch install had withdrawn at 13:36:40 (after the backup); the restore resurrected it, and nothing on the box or in the backup says it must go — it was re-deleted by hand with the snippet in [OPERATOR.md](../../docs/OPERATOR.md#backup-and-restore) only because the operator still held the id (DashBuddy #1192). Box terminated and volume deleted 13:55. Side finding: the same box proved the fix for the blind memory metric (DashBuddy #1191).

Backup objects expire after 14 days; overwritten/noncurrent versions expire 14 days after becoming noncurrent. This is not a strict 14-day maximum from original creation. The bucket policy denies object deletion and lifecycle changes to **every principal except the account root** (including the SSO administrator); S3 lifecycle expiration still operates. Initial apply installs lifecycle before that deny. Later retention edits or teardown require the root-controlled exception/policy procedure; ordinary administrator Terraform cannot change/delete the lifecycle. Do not expect `terraform destroy` to empty this protected bucket. The policy is not Object Lock and an administrator able to change bucket policy can remove it.

## Cost and controls

Planning estimate for a small workload at 730 hours/month in Ohio, before taxes and credits; verify actual regional rates before applying:

| Component | Approximate monthly cost |
| --- | ---: |
| t4g.small Linux, standard CPU credits | $12.30 |
| 20 GiB gp3 root + 20 GiB gp3 data | $3.20 |
| One public IPv4 / Elastic IP | $3.65 |
| Two custom metrics and five standard alarms | $1.10 |
| Small S3 backups/state/audit logs, requests and notifications | $1–2 |
| **Expected small deployment** | **About $22** |

See [EC2 pricing](https://aws.amazon.com/ec2/pricing/on-demand/), [EBS pricing](https://aws.amazon.com/ebs/pricing/), [public IPv4 pricing](https://aws.amazon.com/vpc/pricing/), and [CloudWatch pricing](https://aws.amazon.com/cloudwatch/pricing/). Transfer, large backups, extra CloudTrail copies, and growing data add cost. There is no NAT gateway, load balancer, RDS, or detailed EC2 monitoring.

The account-wide $25 budget emails at 50%/100% actual and 100% forecast. The account-wide $50 budget automatically invokes `AWS-StopEC2Instance` for this instance; its execution role follows the [AWS Budgets SSM role policy](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AWSBudgetsActions_RolePolicyForResourceAdministrationWithSSM.html), narrowed to stopping this instance. Billing data arrives late: **the hard-ceiling action is not a guaranteed $50 spending cap**. Storage, IPv4, and other services keep accruing charges after EC2 stops. Investigate the bill and action status before manually restarting; do not assume a restart is protected by another immediate stop. Cost Anomaly Detection monitors AWS services and emails daily for absolute impact >= $5.

## What this does NOT do yet

- WAF.
- Per-IP rate limiting (S3 stage: custom Caddy build).
- Play Integrity (S5).
- High availability, automatic database recovery, or app/container log shipping.

## Local validation

After changes, with tool execution authorized:

```sh
terraform fmt -check -recursive .
terraform init -backend=false
terraform validate
terraform -chdir=bootstrap init -backend=false
terraform -chdir=bootstrap validate
```

Backend-free validation does not contact AWS, but initialization downloads providers. No account ID or secret belongs in tracked files. Commit generated provider lockfiles after reviewing them.

## First-deploy notes (2026-10-02, the official instance)

- **Cost Anomaly Detection:** a new account already carries a default `Default-Services-Monitor`, and AWS allows exactly one SERVICE-dimension monitor per account, so the first `apply` fails on `aws_ce_anomaly_monitor.services` with "Limit exceeded on dimensional spend monitor creation". Import it instead of creating: `terraform import aws_ce_anomaly_monitor.services $(aws ce get-anomaly-monitors --query "AnomalyMonitors[?MonitorDimension=='SERVICE'].MonitorArn | [0]" --output text)` and re-apply (it is renamed in place).
- **First boot fails until an image exists:** the box runs `docker compose pull` on first start; before the first `v*` release the GHCR package does not exist and the pull is `denied`. Tag a release, wait for `image.yml`, then `systemctl restart census.service` through SSM. The package was public on first push here (linked to the public repo); if a pull is still denied after publishing, set the package visibility to Public in GitHub → Packages.
- Verified end to end: data volume mounted at `/var/lib/census-data`, Parameter Store values read, all three containers healthy, `/readyz` 200, `https://census.dashbuddy.trotter.cloud/v1/policy` 200 with HSTS within a minute of the DNS record (DNS-only, not proxied, in Cloudflare).
