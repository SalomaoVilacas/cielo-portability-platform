import { test } from "node:test";
import assert from "node:assert/strict";
import { eligible, canonical, isActive, submissionKey } from "../src/domain.ts";
test("only open future installments are selectable", () => {
  for (const status of ["PAGA", "RESERVADA", "PORTADA"])
    assert.equal(
      eligible({ status, dataDesconto: "2099-01-01" }, "2026-01-01"),
      false,
    );
  assert.equal(
    eligible({ status: "ABERTA", dataDesconto: "2026-01-01" }, "2026-01-01"),
    false,
  );
  assert.equal(
    eligible({ status: "ABERTA", dataDesconto: "2099-01-01" }, "2026-01-01"),
    true,
  );
});
test("canonical payload normalizes selection order and duplicates", () => {
  assert.equal(
    canonical({ parcelas: ["b", "a", "a"] }),
    canonical({ parcelas: ["a", "b"] }),
  );
});
test("network retries reuse key; changed payload obtains a new key", () => {
  const values = new Map();
  const storage = {
    getItem: (k) => values.get(k),
    setItem: (k, v) => values.set(k, v),
  };
  const key = submissionKey(storage, "a");
  assert.equal(submissionKey(storage, "a"), key);
  assert.notEqual(submissionKey(storage, "b"), key);
});
test("polling considers only nonterminal states active", () => {
  assert.ok(isActive("PENDENTE"));
  assert.ok(isActive("PENDENTE_ENVIO"));
  for (const state of ["CONFIRMADA", "REJEITADA", "FALHA_TECNICA"])
    assert.equal(isActive(state), false);
});
