import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import ts from "typescript";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
const destination = await fs.mkdtemp(path.resolve("tests/.views-"));
async function compile(directory, relative = "") {
    for (const item of await fs.readdir(directory, { withFileTypes: true })) {
        const source = path.join(directory, item.name),
            target = path.join(relative, item.name);
        if (item.isDirectory()) {
            await compile(source, target);
            continue;
        }
        if (!/\.tsx?$/.test(item.name) || item.name === "main.tsx") continue;
        let output = ts.transpileModule(await fs.readFile(source, "utf8"), {
            compilerOptions: {
                jsx: ts.JsxEmit.ReactJSX,
                module: ts.ModuleKind.ESNext,
                target: ts.ScriptTarget.ES2022,
            },
        }).outputText;
        output = output.replace(
            /(from\s+["'])(\.[^"']+)(["'])/g,
            (_, prefix, specifier, suffix) =>
                prefix + specifier.replace(/\.tsx?$/, "").concat(".mjs") + suffix,
        );
        const file = path.join(destination, target.replace(/\.tsx?$/, ".mjs"));
        await fs.mkdir(path.dirname(file), { recursive: true });
        await fs.writeFile(file, output);
    }
}
try {
    await compile("src");
    const { default: App } = await import(pathToFileURL(path.join(destination, "App.mjs")));
    const { default: Summary } = await import(
        pathToFileURL(path.join(destination, "components/OperationSummary.mjs"))
    );
    test("initial form keeps native validation and accessible navigation", () => {
        const html = renderToStaticMarkup(React.createElement(App));
        assert.match(html, /aria-label="Navegação principal"/);
        assert.match(html, /aria-pressed="true"/);
        assert.match(html, /required=""/);
        assert.match(html, /pattern="\[A-Za-z0-9-\]\{1,60\}"/);
        assert.match(html, /Autorizar e consultar agenda/);
        assert.match(html, /disabled=""/);
    });
    test("operation summary preserves historical unknown total and escapes user data", () => {
        const html = renderToStaticMarkup(
            React.createElement(Summary, {
                operation: {
                    detalhes: {
                        solicitacao: {
                            credorCessionario: "<script>bad</script>",
                            parcelas: ["P-1", "P-2"],
                            contaRecebimento: { banco: "237", agencia: "1", conta: "123" },
                        },
                        parcelas: [],
                        valorTotal: null,
                    },
                },
            }),
        );
        assert.match(html, /Indisponível no registro histórico/);
        assert.match(html, /P-1/);
        assert.match(html, /P-2/);
        assert.doesNotMatch(html, /<script>/);
    });
} finally {
    await fs.rm(destination, { recursive: true, force: true });
}
