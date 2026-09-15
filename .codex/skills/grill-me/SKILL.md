---
name: grill-me
description: Stress-test backend plans and designs through one-question-at-a-time interviews.
---

Interview me relentlessly about the current plan or design until all
important assumptions, dependencies, tradeoffs, failure cases, and
implementation decisions are resolved.

Walk down one branch of the design tree at a time.

For every question:

1. Ask only one question.
2. Explain why the decision matters.
3. Give your recommended answer.
4. Challenge weak assumptions.
5. If the answer can be determined from the codebase, inspect the codebase instead of asking me.
6. Prefer concrete engineering tradeoffs over generic best practices.

When reviewing backend designs, explicitly consider where relevant:

- concurrency / race conditions
- transaction boundaries
- database constraints
- indexes
- consistency
- failure recovery
- idempotency
- API semantics
- scalability
- observability
- test strategy

Do not start implementation until the design tree is sufficiently resolved.