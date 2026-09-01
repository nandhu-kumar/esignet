#!/usr/bin/env python3
"""Provision the conformance suite's static clients on a LOCAL eSignet.

The suite authenticates as pre-registered clients whose private JWKS live in
conformance-suite-private/*.json. Those clients exist on whichever deployment
the file was written for -- a freshly started local server has never heard of
them, so every conformance module fails at client authentication and the
coverage panel reports the conformance column as ~0%: a statement about the
fixture, not about the service.

This registers each plan config's client (and client2) against the local server
using the PUBLIC half of the key already in the file, and writes a sibling
<name>.local.json whose discoveryUrl -- and, for FAPI, resourceUrl -- point at
the local server instead. The originals are never modified: they hold the
credentials for a real deployment, and a coverage run must not be able to
damage them.

Idempotent: a client that already exists is updated via PUT rather than
re-created, so re-running against a live stack is safe.

Run through compose (see coverage-docker-compose.yml), not by hand:
    docker compose -f coverage-docker-compose.yml run --rm seed-conformance
"""

import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

# Where the plan configs are mounted, and the server to register against.
PRIVATE_DIR = pathlib.Path(os.environ.get("PLAN_CONFIG_DIR", "/conformance-suite-private"))
ESIGNET = os.environ.get("ESIGNET_BASE_URL", "http://esignet:8088").rstrip("/")
SUITE = os.environ.get("SUITE_BASE_URL", "https://localhost.emobix.co.uk:8443").rstrip("/")
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN", "")

# The public members of an RSA JWK. Everything else in the file (d, p, q, dp,
# dq, qi) is private key material and must never leave this process -- it is
# what the SUITE signs its client assertions with, and the server only ever
# needs the public half to verify them.
PUBLIC_JWK_FIELDS = ("kty", "e", "n", "use", "kid", "alg")

# Which plan config gets which client profile. FAPI2 requires the sender
# constraining and PAR that the plan's variant declares; registering its clients
# without those makes the server accept requests the plan expects it to reject,
# and the modules fail on assertions rather than on anything real.
PLANS = {
    "esignet-config.json": {"fapi": False},
    "esignet-fapi2-config.json": {"fapi": True},
}


def public_jwk(jwk: dict) -> dict:
    missing = [f for f in ("kty", "e", "n") if f not in jwk]
    if missing:
        raise SystemExit(f"jwk is missing {missing}; only RSA keys are supported here")
    return {f: jwk[f] for f in PUBLIC_JWK_FIELDS if f in jwk}


def request(method: str, url: str, body: dict) -> tuple[int, str]:
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    # A local server installs no scope middleware (that needs both ISSUER_URL
    # and JWKS_URL), so this bearer is never inspected -- but the header has to
    # be present for the same code path to run as on a real deployment.
    req.add_header("Authorization", f"Bearer {ADMIN_TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def envelope(request_body: dict) -> dict:
    # requestTime is validated against the server's clock with a few minutes'
    # leeway, so it is stamped per call rather than stored anywhere.
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    return {"requestTime": now, "request": request_body}


def register(client_id: str, jwk: dict, fapi: bool) -> None:
    body = {
        "clientId": client_id,
        "clientName": f"Conformance {client_id}",
        "clientNameLangMap": {"eng": f"Conformance {client_id}"},
        "relyingPartyId": os.environ.get("RELYING_PARTY_ID", "decl-ou-1"),
        "logoUri": "https://example.com/logo.png",
        "redirectUris": [
            f"{SUITE}/test/a/esignet-test/callback",
            f"{SUITE}/test/a/esignet-test/callback?dummy1=lorem&dummy2=ipsum",
            f"{ESIGNET}/userprofile",
        ],
        "userClaims": ["name", "email", "picture", "phone_number", "address", "gender", "birthdate"],
        "authContextRefs": [
            "mosip:idp:acr:generated-code",
            "mosip:idp:acr:password",
            "mosip:idp:acr:biometrics",
            "mosip:idp:acr:knowledge",
        ],
        "publicKey": public_jwk(jwk),
        "grantTypes": ["authorization_code"],
        "clientAuthMethods": ["private_key_jwt"],
    }
    if fapi:
        body["additionalConfig"] = {
            "require_pushed_authorization_requests": True,
            "dpop_bound_access_tokens": True,
            "require_pkce": True,
        }

    status, text = request("POST", f"{ESIGNET}/client-mgmt/client", envelope(body))
    # The server reports a duplicate as 200 with an error body rather than a 4xx,
    # so the decision cannot be made on status alone.
    if status == 200 and "errorCode" not in text:
        print(f"  registered {client_id}")
        return
    if "duplicate" not in text and "already" not in text and status != 409:
        raise SystemExit(f"  register {client_id} failed: HTTP {status}: {text[:400]}")

    # Update takes a DIFFERENT payload from create: no clientId (it is in the
    # path) and no publicKey (a client's key is immutable here), but `status` is
    # required. Sending the create body would be rejected as invalid_input, with
    # nothing naming which field was at fault.
    update = {k: body[k] for k in (
        "clientName", "clientNameLangMap", "logoUri", "redirectUris",
        "userClaims", "authContextRefs", "grantTypes", "clientAuthMethods",
    )}
    update["status"] = "ACTIVE"
    if "additionalConfig" in body:
        update["additionalConfig"] = body["additionalConfig"]

    status, text = request("PUT", f"{ESIGNET}/client-mgmt/client/{client_id}", envelope(update))
    if status == 200 and "errorCode" not in text:
        print(f"  updated {client_id} (already existed)")
        return
    raise SystemExit(f"  update {client_id} failed: HTTP {status}: {text[:400]}")


def main() -> None:
    wrote = []
    for name, opts in PLANS.items():
        path = PRIVATE_DIR / name
        if not path.exists():
            print(f"{name}: not present, skipping")
            continue
        print(f"{name}:")
        cfg = json.loads(path.read_text(encoding="utf-8"))

        for key in ("client", "client2"):
            client = cfg.get(key)
            if not client:
                continue
            keys = client.get("jwks", {}).get("keys") or []
            if not keys:
                raise SystemExit(f"  {key} has no jwks.keys")
            register(client["client_id"], keys[0], opts["fapi"])

        # A copy pointed at the local server. Written beside the original rather
        # than over it: the original carries the credentials for a real
        # deployment and stays usable for a run against one.
        cfg.setdefault("server", {})["discoveryUrl"] = f"{ESIGNET}/.well-known/openid-configuration"
        if "resource" in cfg:
            cfg["resource"]["resourceUrl"] = f"{ESIGNET}/oauth2/userinfo"
        out = path.with_suffix(".local.json")
        out.write_text(json.dumps(cfg, indent=2) + "\n", encoding="utf-8")
        print(f"  wrote {out.name} -> {ESIGNET}")
        wrote.append(out.name)

    if not wrote:
        raise SystemExit("no plan configs found — is conformance-suite-private/ mounted?")
    print("conformance clients: done")


if __name__ == "__main__":
    sys.exit(main())
