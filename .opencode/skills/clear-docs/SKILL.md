---
name: clear-docs
description: Reorganize and prune project documentation across philosophy, architecture decision records (ADRs), active plans, and agent entrypoints using strict scope boundaries and git history pruning. Use when auditing, reorganizing, or cleaning up repository docs, separating principles from structural decisions and active tasks, pruning completed history from plans, or structuring agent instructions.
---

# Doc Layer Organizer

## Summary

Audit, separate, and prune repository documentation so each layer holds only its rightful scope without bloat or overlap. Move completed history into git history rather than keeping dead weight in active planning files.

## When to Use

Use this skill when:
- Reorganizing or auditing project documentation across philosophy, ADRs, plans, and agent instruction files.
- Separating high-level principles from structural decisions, specifications, or active tasks.
- Pruning completed phases or closed history from an active plan file.
- Structuring or refining an agent entrypoint file as a reference hub with concise operational guardrails.

## Steps

1. **Inspect existing docs and boundaries:** Read the target philosophy, ADR, plan, and agent entrypoint files as well as any read-only or restricted path rules in the repository. Note any file marked read-only so proposed changes to it are reported clearly instead of written without permission.

2. **Classify every item with the layer logic:**
   - **Philosophy:** Keep only foundational principles, values, and abstract long-term posture that guide judgment without tying to specific files, commands, binary layouts, phases, or temporary tools. If a rule is a structural boundary, move it to ADR; if it is an agent workflow rule, move it to the agent entrypoint; if it is a detailed format or spec, move it to a dedicated spec doc; if it is current work or closed history, move it to the plan or prune it to git history.
   - **ADR:** Keep structural or architectural decisions with explicit status (`Accepted`, `Amended`, `Superseded`, `Proposed`), context, concrete invariants, and consequences. Exclude task roadmaps, closed sub-slice logs, unaccepted proposals disguised as settled contracts, and multi-page technical specs that belong in dedicated specification files.
   - **Plan:** Keep only current operational state, active work fronts, immediate next steps, concrete completion criteria, and active blockers. Aggressively prune completed phases, closed bug fixes, finished inventories, and long historical narratives already preserved in git history. Order top-down by immediate action.
   - **Agent entrypoint:** Keep a clear reference hub mapping key documents and areas with short role descriptions, paired with concise, unambiguous execution guardrails (precedence, verification commands, git limits, compaction/context rules). Avoid duplicating full specs, ADRs, or plan bodies.

3. **Apply clean edits and remove duplicates:** Update editable files so each rule lives in one primary place without conflicting copies. Keep references explicit when one layer points to another.

4. **Report changes and pending approvals:** Summarize what moved between files, what was pruned to git history, what contradictions or duplicates were removed, and any edits held back due to read-only or permission constraints.

## Gotchas

- **Leaving closed history in active plans:** Large logs of finished work inflate context and bury immediate next steps. Prune completed history aggressively when git already records it.
- **Turning ADRs into manuals or roadmaps:** Keep ADRs focused on structural decisions and invariants rather than full subsystem specs or task lists.
- **Overloading philosophy with workflow habits:** Keep operational commands, paths, and agent mechanics out of long-term philosophy unless they define an abstract design principle.
- **Editing restricted files blindly:** Respect read-only or protected documentation paths and surface exact proposals when explicit approval is required.
