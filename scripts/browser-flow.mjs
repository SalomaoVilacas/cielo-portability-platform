import assert from "node:assert/strict";
export async function verifyPortability(page, url) {
    await page.goto(url);
    await page
        .getByLabel("Identificador do beneficiário", { exact: true })
        .fill("BROWSER-" + Date.now());
    assert.equal(
        await page.getByRole("button", { name: "Autorizar e consultar agenda" }).isEnabled(),
        false,
    );
    await page.getByRole("checkbox", { name: "Autorizo a consulta", exact: false }).check();
    await page.getByRole("button", { name: "Autorizar e consultar agenda" }).click();
    const parcels = page.getByRole("checkbox", { name: /^Selecionar / });
    await parcels.first().waitFor({ state: "visible" });
    await parcels.nth(0).check();
    await parcels.nth(1).check();
    await page.getByLabel("Banco (3 dígitos)", { exact: true }).fill("237");
    await page.getByLabel("Agência", { exact: true }).fill("0002");
    await page.getByLabel("Conta com dígito", { exact: true }).fill("98765-4");
    await page.getByRole("button", { name: "Solicitar portabilidade", exact: true }).click();
    await page
        .getByRole("cell", { name: "Confirmada", exact: true })
        .last()
        .waitFor({ state: "visible", timeout: 30000 });
    assert.ok(await page.getByText("Cielo", { exact: true }).isVisible());
    await page.getByRole("button", { name: "Nova portabilidade", exact: true }).click();
    await page
        .getByRole("cell", { name: "Portada", exact: true })
        .first()
        .waitFor({ state: "visible", timeout: 15000 });
    assert.equal(await page.getByRole("cell", { name: "Portada", exact: true }).count(), 2);
    assert.equal(await parcels.nth(0).isEnabled(), false);
    assert.equal(await parcels.nth(1).isEnabled(), false);
    await page.reload();
    await page.getByRole("button", { name: "Histórico e auditoria", exact: true }).click();
    await page
        .getByRole("cell", { name: "Confirmada", exact: true })
        .last()
        .waitFor({ state: "visible" });
    console.log(
        "PASS browser: consent, native form, two installments, confirmation, automatic agenda invalidation, details and history after reload",
    );
}
