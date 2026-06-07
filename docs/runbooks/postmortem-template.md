# Post-Incident Review Template

> **Blameless by default.** The goal is to understand what happened and prevent recurrence,
> not to assign fault. Systems fail; the question is why, and how we make them fail less.
>
> Fill this out within 48 hours of incident resolution while memory is fresh.
> Share with the full team regardless of who was on-call.

---

## Incident summary

| Field | Value |
|-------|-------|
| **Incident ID** | INC-YYYY-NNN |
| **Severity** | P1 / P2 / P3 |
| **Date / time** | YYYY-MM-DD HH:MM IST |
| **Duration** | X hours Y minutes |
| **Services affected** | e.g. booking-service, payment-service |
| **Customer impact** | e.g. "Bookings could not be confirmed for 45 minutes" |
| **Lead responder** | |
| **Reviewers** | |

---

## Timeline

All times in IST. Be specific — "around 2pm" is useless for a postmortem.

| Time | Event |
|------|-------|
| HH:MM | Alert fired / first sign of issue |
| HH:MM | On-call paged |
| HH:MM | Incident declared, responders assembled |
| HH:MM | Root cause identified |
| HH:MM | Mitigation applied |
| HH:MM | Service restored to normal |
| HH:MM | Incident closed |

---

## What happened

_Write 2-4 sentences describing what customers experienced and what the system did wrong.
Be concrete: "Booking confirmations failed with HTTP 503" not "there were errors"._

---

## Root cause

_One sentence. Not "the server was slow" — that is a symptom. Root cause is the specific
code, config, infrastructure, or process condition that made this failure possible._

**Root cause:** ...

**Contributing factors** (things that made the incident worse or detection slower):
- ...
- ...

---

## Detection

- How was this discovered? (alert, customer complaint, manual check, Slack message)
- Which Grafana panel / Prometheus alert fired first?
- How long between the start of impact and the first page? (detection lag)
- If detection lag > 5 minutes: why? Is an alert missing or mis-configured?

---

## Response

- What was the first mitigation tried? Did it work?
- What runbook steps were followed? Did they cover this scenario?
- Were there any wrong turns that added time to resolution?
- Were the right people reachable? (if someone was unreachable, note it)

---

## Impact quantification

| Metric | Value |
|--------|-------|
| Bookings lost / delayed | |
| Payment attempts failed | |
| Customers affected (estimate) | |
| Revenue impact (estimate) | |
| SLA breach? | Yes / No |

---

## What went well

_Things that worked as intended and limited the blast radius. Be specific._

- ...

---

## What went poorly

_Things that made the incident worse or response slower. No blame — systemic issues only._

- ...

---

## Action items

Each item must have an owner and a deadline. Unowned action items do not get done.

| # | Action | Owner | Due | Tracking |
|---|--------|-------|-----|----------|
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |

**Categories to consider:**
- Detection: add/tune an alert so this is caught faster next time
- Prevention: code fix, config guard, or validation that prevents recurrence
- Runbook: update operational-runbooks.md with the scenario and fix steps
- Process: on-call rotation, escalation path, communication template

---

## Was the runbook adequate?

- [ ] Runbook covered this scenario — response was smooth
- [ ] Runbook covered this partially — gaps identified in action items above
- [ ] Runbook did not cover this — new runbook entry needed (action item created)
- [ ] No runbook existed for this service/scenario

---

## Sharing

- [ ] Postmortem shared with engineering team in Slack / email
- [ ] Relevant action items added to the sprint / issue tracker
- [ ] If customer-visible: customer communication sent
