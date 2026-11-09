"""Read-only comparison of backend history with signed Central agendas. Never calls status endpoints."""
import hashlib
import hmac
import json
import os
import time
import urllib.request

backend = os.environ.get("BACKEND_URL", "http://localhost:8080")
central = os.environ.get("CENTRAL_URL", "http://localhost:8081")
secret = os.environ.get("INTEGRATION_SECRET", "local-demo-secret-change-for-production")
def get(base, path, signed=False):
    headers = {}
    if signed:
        timestamp = str(int(time.time()))
        signature = hmac.new(secret.encode(), f"GET\n{path}|\n{timestamp}\n".encode(), hashlib.sha256).hexdigest()
        headers = {"X-Timestamp": timestamp, "X-Signature": signature}
    with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=5) as response:
        raw = response.read().decode()
        if signed:
            message = f"{response.status}\n{response.headers.get('Retry-After', '')}\n{raw}"
            expected = hmac.new(secret.encode(), message.encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, response.headers.get("X-Signature", "")):
                raise ValueError("Invalid Central response signature")
        return json.loads(raw)
try:
    operations = get(backend, "/api/v1/portabilities")
    mismatches = 0
    for beneficiary in sorted({operation["beneficiarioId"] for operation in operations}):
        agenda = {parcel["parcelaId"]: parcel for parcel in get(central, "/beneficiarios/" + beneficiary + "/agenda", True)}
        for operation in operations:
            if operation["beneficiarioId"] != beneficiary or operation["status"] != "CONFIRMADA":
                continue
            details = operation.get("detalhes")
            if not details:
                print("Details unavailable; restart using current build:", operation["id"])
                continue
            request = details["solicitacao"]
            for parcel_id in request["parcelas"]:
                parcel = agenda.get(parcel_id)
                consistent = parcel and parcel["status"] == "PORTADA" and parcel["contaRecebimento"] == request["contaRecebimento"]
                print(operation["id"], parcel_id, "CONSISTENT" if consistent else "DIVERGENT")
                mismatches += not bool(consistent)
    print("Divergences:", mismatches)
    raise SystemExit(1 if mismatches else 0)
except (OSError, ValueError) as error:
    raise SystemExit("Diagnostic unavailable (no data changed): " + str(error))
