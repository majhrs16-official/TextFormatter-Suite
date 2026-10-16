# Assembler validation cases

Curated fixtures that pin down the `.flowasm` assembler's validation rules:
which inputs must assemble (`valid-*`) and which must be rejected with a
specific diagnostic (`invalid-*`).

Each case is declared in [`cases.json`](cases.json):

| field    | meaning                                                        |
|----------|----------------------------------------------------------------|
| `file`   | fixture in this directory                                      |
| `expect` | `ok` (must assemble) or `error` (must fail)                    |
| `match`  | optional substring that the diagnostic output must contain     |

## Running

```bash
npm run build              # produces dist/cli.js (if src changed)
npm run test:validation    # node test/validation/run.js
```

The runner shells out to `dist/cli.js` for every case and exits non-zero if
any expectation is not met. It has no external dependencies.
