# Runbook — purge `admin_token.txt` / `stress-tokens.txt` from git history

**Register item:** P0-2 · **Re-assessed severity: P1 (repository hygiene), not P0 (live credential leak).** The evidence for that downgrade is in §1 — read it before deciding how urgently to act.

**Status:** ✅ untracked from the index (2026-08-01) · ❌ **history purge: RECOMMENDED AGAINST — see §0.** The runbook below is retained for the cases where it *would* be right.

---

## 0. Decision (2026-08-01): do not rewrite history for this

Two facts, established by measurement, invert the usual advice.

**Fact 1 — the repository is already public.** `git ls-remote` succeeds anonymously against `https://github.com/AdarshKirito/sk-bingegalaxy.git`. These files have been world-readable for months. A history rewrite **cannot un-publish them**: forks, existing clones, GitHub's fork network, and third-party code-search indexes all retain the blobs independently of anything done to this remote. The usual justification for a purge — "get it out before anyone sees it" — no longer applies.

**Fact 2 — the data is worthless.** The tokens expired **88 and 98 days ago** (900-second TTL) and the signing key is **384-bit and non-default**, so nothing can be replayed or derived. There is no secret left to protect.

**Cost, against that zero benefit:**

| Cost | Detail |
|---|---|
| Every commit SHA changes | Breaks every existing clone, every issue/PR reference to a SHA, every deploy pinned to one |
| **8 remote branches** | Mostly dependabot. They keep the *old* history; merging any one of them **reintroduces the purged blobs**, silently undoing the exercise |
| Force-push to a public repo | Loses the safety of branch protection during the window |
| **140 uncommitted files locally** | `git filter-repo` refuses a dirty tree; the required re-clone would destroy this work if it were not committed first |
| Blobs remain fetchable by SHA | Until GitHub garbage-collects — a support request, not a command |

**Conclusion: the risk-reduction is zero and the disruption is high.** The correct response to already-public credentials is *rotation*, and rotation is unnecessary here because they are expired. What actually mattered was stopping recurrence — done in §3.

### When you WOULD run the purge below

- Before making a **currently private** repository public
- If a **live, unexpired** credential is committed — **rotate first**, then purge
- If committed material is legally sensitive (personal data, licensed code) rather than merely a dead secret
- As part of a planned history rewrite you are doing anyway

If any of those apply, §4 is complete and ready.

---

## 1. What the exposure actually is

Earlier audit documents describe this as leaked credentials requiring immediate rotation. Measured against the artefacts, that overstates it:

| Question | Finding |
|---|---|
| Is the repo public? | **Yes** — anonymous `git ls-remote` succeeds. Exposure already happened; see §0. |
| What are the files? | JWTs — three tokens total. `admin_token.txt`: one `SUPER_ADMIN`. `stress-tokens.txt`: one `SUPER_ADMIN`, one `CUSTOMER`. |
| Token lifetime | `exp − iat` = **900 s (15 minutes)** |
| Are they still valid? | **No.** `admin_token.txt` expired ~**88 days** ago; `stress-tokens.txt` ~**98 days** ago. **Replay is impossible.** |
| Can the signing key be recovered from them? | A JWT is a known message/signature pair, so a *weak* HMAC key could be brute-forced offline. **`JWT_SECRET` here is 48 decoded bytes (384-bit) and is not the shipped placeholder**, so this is computationally infeasible. |
| Do they reveal anything else? | Claim structure (`role`, `iat`, `exp`, `sub`) — already inferable from the public source. |

**Conclusion: no rotation is required, and there is no live exposure.** What remains is genuine but ordinary hygiene: a repository must not carry credentials, because the next set might not be expired and the next signing key might not be strong.

> This is a **point-in-time** assessment. Re-run §2 before publishing the repository, and re-assess immediately if `JWT_SECRET` is ever weakened or the token TTL lengthened.

## 2. Re-verify before acting

```bash
# Are the files still reachable from any ref?
git log --oneline --all -- admin_token.txt stress-tokens.txt

# Decode ONLY the exp claim (never paste a whole token into a shell you don't control)
for f in admin_token.txt stress-tokens.txt; do
  grep -o '[A-Za-z0-9_-]\{20,\}\.[A-Za-z0-9_-]\{20,\}\.[A-Za-z0-9_-]\{10,\}' "$f" | while read t; do
    p=$(echo "$t" | cut -d. -f2); pad=$(( (4 - ${#p} % 4) % 4 ))
    echo "$p$(printf '=%.0s' $(seq 1 $pad))" | base64 -d 2>/dev/null | grep -o '"exp":[0-9]*'
  done
done
date +%s   # anything smaller than this is expired
```

## 3. Root cause — fixed 2026-08-01

`stress-test-26apr.ps1:35` mints tokens by logging in and then **persists them to a repo-relative path**:

```powershell
"ADMIN=$adminTok`nCUST=$custTok`nID=$custId`nEMAIL=$email" | Out-File -FilePath stress-tokens.txt
```

Four other scripts (`stress-round2/3`, `stress-run-26apr`, `stress-pwn-test`) read it back.

`.gitignore` **already listed both files** (lines 25–27) — and had no effect, because **gitignore never applies to a file git is already tracking**. That single gotcha is why this item stayed open across multiple audits: the ignore rule looked like the fix.

**What was done:**
```bash
git rm --cached admin_token.txt stress-tokens.txt
```
Untracks them while **leaving them on disk**, so all five stress scripts keep working unchanged, and the pre-existing `.gitignore` entries now actually bite. A future stress run recreates the file locally and git will refuse to see it.

**Commit that staged deletion** — until it is committed, the files are still in `HEAD`.

## 4. The history purge (destructive — requires a decision)

Only needed **before making the repository public or granting outside access**. It is not urgent for a private repo given §1.

### Preconditions
- [ ] Every collaborator has pushed; no unmerged work anywhere
- [ ] A full mirror backup exists (below) and has been verified
- [ ] Open PRs noted — rewriting history invalidates every one of them
- [ ] A window agreed: **every clone must be re-cloned afterwards**
- [ ] Branch protection / force-push rules temporarily relaxed on the remote

### Backup first — this step is not optional
```bash
git clone --mirror <remote-url> ../skbg-backup-$(date +%Y%m%d).git
git -C ../skbg-backup-$(date +%Y%m%d).git log --oneline -1   # verify it is real
```

### Purge
`git filter-repo` is the supported tool (`git filter-branch` is deprecated and slow):

```bash
pip install git-filter-repo   # once

git filter-repo \
  --path admin_token.txt \
  --path stress-tokens.txt \
  --invert-paths
```

### Verify
```bash
git log --all --oneline -- admin_token.txt stress-tokens.txt   # must be EMPTY
git count-objects -v
```

### Publish
```bash
git remote add origin <remote-url>     # filter-repo removes the remote deliberately
git push --force --all
git push --force --tags
```

### Aftermath — tell the team explicitly
Every collaborator must **re-clone**. A `git pull` onto old history creates a merge that reintroduces the purged blobs, silently undoing the whole exercise.

```bash
# each collaborator
cd .. && rm -rf sk-binge-galaxy && git clone <remote-url>
```

Also ask the host (GitHub/GitLab) to **garbage-collect unreachable objects** — until they do, the old blobs remain fetchable by SHA even after a successful force-push.

## 5. Related, still open

| Item | Note |
|---|---|
| Uncommitted working tree | A large change set is uncommitted. **Commit before any history rewrite** — `filter-repo` refuses to run on a dirty tree, and losing this work to a forced re-clone would be worse than the problem being solved. |
| `PR-PAY-01` | Unrelated P0 launch gate: no provider-sandbox proof for payments/refunds. |
| Internal API secret | Audited 2026-08-01 — **not exposed**. See [../distribution/04-SECURITY-AND-VERIFICATION-LOG.md](../distribution/04-SECURITY-AND-VERIFICATION-LOG.md) §SEC-2. |
