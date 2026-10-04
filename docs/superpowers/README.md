# Superpowers archive

Dated design specs and the execution plans that implemented them.

This is not a dumping ground and it is not current product status. Each
file is a snapshot: what was designed, or what a session planned to
build, on that day. The shipping picture lives in
[`../APP_PLAN.md`](../APP_PLAN.md) and the root [`README.md`](../../README.md).

## Layout

- [`specs/`](specs/) — design documents, named
  `YYYY-MM-DD-<topic>-design.md` (or a close variant).
- [`plans/`](plans/) — the work that followed, named
  `YYYY-MM-DD-<topic>.md`, often phased (`-phase-1`, `-r1`).

A spec and a plan with the same date and topic are a pair. Later rounds
of the same engine keep their own files rather than rewriting the first.

## How to read one

1. The filename date is the claim date.
2. A "not yet" / "cannot compile `:app`" / engine-count sentence is
   what was true then. Check the code, or `APP_PLAN.md`, before
   repeating it.
3. Do not delete a spec because the work shipped. The plan's "what was
   tried and abandoned" is the reason the next session does not retry it.

## Do not

- Treat a plan's task list as open work. If it shipped, the tick is in
  `FEATURE_PLAN.md` or the code.
- Move these files to tidy `docs/`. The ISO prefix is the index.
- Rewrite a snapshot to match today's engine count. Add a one-line
  banner at the top if a sentence has started to mislead.
