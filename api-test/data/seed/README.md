# Seed data for a local mock-identity-system

Each `*.json` here is **one identity**, exactly as
`POST /v1/mock-identity-system/identity` wants its `request` object — no wrapper,
no comments. The `seed-identity` service in
[`coverage-docker-compose.yml`](../../coverage-docker-compose.yml) posts every
file in this directory, wrapping each in the `{requestTime, request}` envelope at
send time.

Two things are deliberate:

- **No `_comment` key.** The rest of `data/config/` uses that convention because
  the harness ignores unknown keys. This endpoint does not — it answers
  `Unrecognized field "_comment" ... not marked as ignorable` — so the
  documentation lives here instead of inside the payload.
- **`requestTime` is not in the file.** The service validates it against its own
  clock and rejects a stale value, so a committed timestamp would work once and
  then fail forever. The seeder stamps it per run.

## Why this exists

A deployed environment (esqa and friends) already has identities loaded. A
`mock-identity-system` container started from the published image has none, so
without seeding, every login in the e2e surface fails with
`invalid_individual_id (Send OTP failed)` — and the coverage panel then reports
the flow packages as barely reached, which is a statement about the fixture
rather than about the service.

## Keeping it in step with the config

The values must match whichever cover config is being run —
`data/config/config.cover.mock.json` uses:

| Config field | Value | Seed field |
|---|---|---|
| `esignet.identity.individual_id` | `8267411574` | `individualId` |
| `esignet.identity.id_type` | `phone` | `phone` |
| `esignet.credentials.password` | `Mosip@123` | `password` |
| `esignet.otp.value` | `111111` | `pin` |

Change one and you must change the other; nothing checks the two agree, and a
mismatch surfaces as a failed login rather than as a configuration error.

The remaining fields are simply everything the schema marks required — see
`GET /v1/mock-identity-system/identity/identity-schema` on a running container.
`encodedPhoto` is a 1×1 PNG; the flows only need the field present and
decodable.

**Synthetic data only.** These files are tracked, so they must never hold
anything resembling a real person's details.
