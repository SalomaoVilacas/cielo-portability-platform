"""Real HTTP contract tests. Uses temporary file databases and owns only its child processes."""
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
SECRET = "integration-test-secret"
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"

def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

def request(base, path, payload=None, key=None, signed=False, expected=200):
    body = "" if payload is None else json.dumps(payload, separators=(",", ":"))
    method = "GET" if payload is None else "POST"
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    if signed:
        stamp = str(int(time.time()))
        headers["X-Timestamp"] = stamp
        headers["X-Signature"] = hmac.new(SECRET.encode(), f"{method}\n{path}|{key or ''}\n{stamp}\n{body}".encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(base + path, data=body.encode() if payload is not None else None, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        assert response.status == expected, (path, response.status, raw)
        if signed:
            retry = response.headers.get("Retry-After", "")
            sig = hmac.new(SECRET.encode(), f"{response.status}\n{retry}\n{raw}".encode(), hashlib.sha256).hexdigest()
            assert hmac.compare_digest(sig, response.headers.get("X-Signature", "")), "Response integrity"
        return json.loads(raw) if raw else None

def wait_health(base, process):
    for _ in range(150):
        if process.poll() is not None:
            raise AssertionError("Service exited; inspect logs in the printed temporary directory")
        try:
            request(base, "/actuator/health")
            return
        except (OSError, AssertionError):
            time.sleep(.1)
    raise AssertionError("Health timeout")

def wait_state(base, operation, expected):
    for _ in range(160):
        value = request(base, "/api/v1/portabilities/" + operation["id"])
        if value["status"] == expected:
            return value
        time.sleep(.1)
    raise AssertionError((expected, value))

def main():
    with tempfile.TemporaryDirectory(prefix="cielo-e2e-") as directory:
        work = Path(directory)
        print("Isolated test files:", directory, flush=True)
        central_port, backend_port = free_port(), free_port()
        central_url, backend_url = f"http://127.0.0.1:{central_port}", f"http://127.0.0.1:{backend_port}"
        children = []
        logs = []
        def start(module, port, extra=()):
            jar = ROOT / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar"
            log = open(work / f"{module}-{len(children)}.log", "w+")
            logs.append(log)
            process = subprocess.Popen([JAVA, "-jar", str(jar), f"--server.port={port}", f"--spring.datasource.url=jdbc:h2:file:{work/module};DB_CLOSE_ON_EXIT=FALSE", f"--integration.secret={SECRET}", *extra], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            children.append(process)
            wait_health(f"http://127.0.0.1:{port}", process)
            return process
        settings = (f"--integration.central-url={central_url}", "--integration.retry-base-ms=50", "--integration.max-attempts=3")
        try:
            start("central-mock", central_port, ("--central.confirmation-delay-ms=1800",))
            backend = start("backend", backend_port, settings)
            request(backend_url, "/api/v1/beneficiaries/NO-CONSENT/agenda", expected=403)
            request(backend_url, "/api/v1/authorizations", {}, expected=400)
            request(central_url, "/beneficiarios/B/agenda", expected=401)
            def authorize(id):
                payload = {"beneficiarioId": id, "convenioId": "CONV"}
                a = request(backend_url, "/api/v1/authorizations", payload, expected=201)
                b = request(backend_url, "/api/v1/authorizations", payload, expected=201)
                assert a == b
                return request(backend_url, f"/api/v1/beneficiaries/{id}/agenda")
            def submit(id, scenario="CONFIRMAR", key=None):
                payload = {"beneficiarioId": id, "parcelas": [f"{id}-CONV-1"], "credorCessionario": "Cielo", "contaRecebimento": {"banco": "237", "agencia": "0002", "conta": "98765-4"}, "cenario": scenario}
                op = request(backend_url, "/api/v1/portabilities", payload, key or id, expected=202)
                return op, payload
            authorize("SUCCESS")
            op, payload = submit("SUCCESS")
            with concurrent.futures.ThreadPoolExecutor(4) as pool:
                results = list(pool.map(lambda _: request(backend_url, "/api/v1/portabilities", payload, "SUCCESS", expected=202), range(8)))
            assert all(r["id"] == op["id"] for r in results)
            changed = dict(payload, credorCessionario="Outro")
            request(backend_url, "/api/v1/portabilities", changed, "SUCCESS", expected=409)
            request(backend_url, "/api/v1/portabilities", payload, "ANOTHER", expected=409)
            wait_state(backend_url, op, "CONFIRMADA")
            agenda = request(backend_url, "/api/v1/beneficiaries/SUCCESS/agenda")
            assert agenda[0]["status"] == "PORTADA"
            assert agenda[0]["contaRecebimento"]["conta"] == "98765-4"
            history = request(backend_url, "/api/v1/portabilities/" + op["id"] + "/events")
            assert [e["tipo"] for e in history] == ["SOLICITADA", "PENDENTE", "CONFIRMADA"]
            print("PASS confirmation, account update, concurrent idempotency, payload conflict, audit", flush=True)
            authorize("REJECT")
            rejected, _ = submit("REJECT", "REJEITAR")
            wait_state(backend_url, rejected, "REJEITADA")
            assert request(backend_url, "/api/v1/beneficiaries/REJECT/agenda")[0]["contaRecebimento"]["banco"] == "001"
            submit("REJECT", key="REJECT-NEW")
            print("PASS rejection preserves account and releases reservations", flush=True)
            for scenario in ["INDISPONIVEL", "RESPOSTA_INVALIDA", "ASSINATURA_INVALIDA"]:
                authorize(scenario.replace("_", "-"))
                failed, _ = submit(scenario.replace("_", "-"), scenario)
                wait_state(backend_url, failed, "FALHA_TECNICA")
                events = request(backend_url, "/api/v1/portabilities/" + failed["id"] + "/events")
                assert sum(e["tipo"] == "RETRY" for e in events) == 2
                print("PASS", scenario, "bounded retries and technical failure", flush=True)
            authorize("TIMEOUT")
            slow, _ = submit("TIMEOUT", "TIMEOUT")
            wait_state(backend_url, slow, "CONFIRMADA")
            print("PASS timeout after acceptance reconciles without duplicate", flush=True)
            authorize("RESTART")
            pending, _ = submit("RESTART")
            backend.terminate()
            backend.wait(timeout=15)
            backend = start("backend", backend_port, settings)
            wait_state(backend_url, pending, "CONFIRMADA")
            assert len(request(backend_url, "/api/v1/portabilities")) == 8
            print("PASS restart recovers persisted operation and history", flush=True)
            # Direct signed Central requests verify both directions, including 204 + Retry-After.
            request(central_url, "/autorizacoes", {"beneficiarioId": "DIRECT", "convenioId": "CONV"}, signed=True, expected=201)
            direct = {"beneficiarioId": "DIRECT", "parcelas": ["DIRECT-CONV-1"], "credorCessionario": "Cielo", "contaRecebimento": {"banco": "237", "agencia": "1", "conta": "123"}, "cenario": "CONFIRMAR"}
            a = request(central_url, "/operacoes-portabilidade", direct, "DIRECT", True, 202)
            b = request(central_url, "/operacoes-portabilidade", direct, "DIRECT", True, 202)
            assert a == b
            request(central_url, "/operacoes-portabilidade/" + a["protocolo"], signed=True, expected=204)
            print("PASS signed Central contract and pending Retry-After", flush=True)
            print("ALL END-TO-END CHECKS PASSED", flush=True)
        except BaseException:
            for log in logs:
                log.flush()
                log.seek(0)
                print(log.read()[-5000:])
            raise
        finally:
            for process in children:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
            for log in logs:
                log.close()

if __name__ == "__main__":
    main()
